package org.trybunal.tool.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Downloads a remote file into a sandboxed directory, enforcing safety constraints
 * suitable for an unsupervised agent.
 *
 * <p><b>Safety contract.</b> This tool:
 * <ul>
 *   <li>Refuses non-http(s) schemes and any host resolving to a private/loopback address (SSRF guard).
 *   <li>Rejects disallowed {@code Content-Type} values.
 *   <li>Aborts and deletes the temp file if the response body exceeds {@code max_bytes}.
 *   <li>Sanitises the destination filename and verifies the resolved path stays inside the sandbox.
 *   <li>De-duplicates by SHA-256: if the sandbox already contains a file with the same hash,
 *       the existing path is returned and the temp file is discarded.
 * </ul>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class SafeDownloadTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(SafeDownloadTool.class);

    private static final String NAME = "safe_download";
    private static final int DEFAULT_MAX_BYTES = 50 * 1024 * 1024;   // 50 MiB
    private static final int SCHEMA_MIN_BYTES = 1024;
    private static final int SCHEMA_MAX_BYTES = 100 * 1024 * 1024;   // 100 MiB
    private static final int MAX_REDIRECTS = 5;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private static final List<String> ALLOWED_CT_PREFIXES = List.of(
            "application/pdf",
            "application/zip",
            "application/vnd.openxmlformats-officedocument",
            "application/vnd.ms-excel",
            "application/json",
            "application/xml",
            "application/octet-stream",
            "text/"
    );

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Download a remote file (PDF, spreadsheet, transcript, etc.) into a sandboxed "
                    + "directory. Refuses private addresses, blocks path traversal, caps size at "
                    + "50 MiB by default, and returns a Source with SHA-256 + local path.",
            buildSchema());

    private final HttpClient http;
    private final Path sandboxRoot;
    private final boolean skipHostGuard;

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public SafeDownloadTool() {
        this(defaultClient(), Sandbox.root(), false);
    }

    /** Package-private testing constructor. */
    SafeDownloadTool(HttpClient http, Path sandboxRoot, boolean skipHostGuardForTests) {
        this.http = http;
        this.sandboxRoot = sandboxRoot;
        this.skipHostGuard = skipHostGuardForTests;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        Object urlArg = arguments == null ? null : arguments.get("url");
        if (urlArg == null || urlArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: url");
        }

        URI uri;
        try {
            uri = URI.create(urlArg.toString().strip());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid url: " + e.getMessage());
        }

        // SSRF guard
        try {
            assertHttp(uri);
            if (!skipHostGuard) {
                assertPublicHost(uri);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Refused: " + e.getMessage());
        }

        // max_bytes
        int maxBytes = DEFAULT_MAX_BYTES;
        Object mb = arguments.get("max_bytes");
        if (mb != null) {
            try {
                maxBytes = ((Number) mb).intValue();
            } catch (ClassCastException ignored) {
                try {
                    maxBytes = Integer.parseInt(mb.toString());
                } catch (NumberFormatException e) {
                    return ToolResult.error("Invalid max_bytes: " + mb);
                }
            }
            if (maxBytes < SCHEMA_MIN_BYTES) maxBytes = SCHEMA_MIN_BYTES;
            if (maxBytes > SCHEMA_MAX_BYTES) maxBytes = SCHEMA_MAX_BYTES;
        }

        // filename_hint
        Object hintArg = arguments.get("filename_hint");
        String hint = (hintArg != null && !hintArg.toString().isBlank())
                ? hintArg.toString().strip()
                : lastSegment(uri);

        Path finalPath;
        try {
            finalPath = Sandbox.resolveSafeChild(sandboxRoot, hint);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Rejected filename: " + e.getMessage());
        }

        // Follow redirects manually (up to MAX_REDIRECTS)
        URI current = uri;
        HttpResponse<InputStream> resp;
        try {
            resp = followRedirects(current, maxBytes);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Refused redirect target: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Fetch failed: " + e.getMessage());
        }

        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            drainQuietly(resp);
            return ToolResult.error("HTTP " + status + " from " + uri);
        }

        // Content-Type check
        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        String ctBase = contentType.split(";", 2)[0].trim();
        if (!isAllowedContentType(ctBase)) {
            drainQuietly(resp);
            return ToolResult.error("Disallowed Content-Type: "
                    + (contentType.isEmpty() ? "<missing>" : contentType));
        }

        // Stream to temp file while computing SHA-256
        Path tempFile = sandboxRoot.resolve("." + UUID.randomUUID() + ".tmp");
        String sha256;
        long totalBytes;
        try {
            DownloadResult dl = streamToTemp(resp.body(), tempFile, maxBytes);
            sha256 = dl.sha256;
            totalBytes = dl.bytes;
        } catch (SizeLimitExceededException e) {
            deleteQuietly(tempFile);
            return ToolResult.error("Response exceeded " + maxBytes + " bytes; download aborted");
        } catch (IOException e) {
            deleteQuietly(tempFile);
            return ToolResult.error("Download failed: " + e.getMessage());
        }

        // De-dupe: if the final path already exists with same SHA-256, reuse it
        if (Files.exists(finalPath)) {
            String existingSha;
            try {
                existingSha = sha256File(finalPath);
            } catch (IOException e) {
                existingSha = "";
            }
            if (sha256.equals(existingSha)) {
                deleteQuietly(tempFile);
                return buildResult(uri, finalPath, sha256, totalBytes);
            }
        }

        // Atomic rename temp → final
        try {
            Files.move(tempFile, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            return ToolResult.error("Failed to save file: " + e.getMessage());
        }

        return buildResult(uri, finalPath, sha256, totalBytes);
    }

    private HttpResponse<InputStream> followRedirects(URI start, int maxBytes)
            throws IOException, InterruptedException {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest req = HttpRequest.newBuilder(current)
                    .GET()
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "*/*")
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                Optional<String> loc = resp.headers().firstValue("Location");
                drainQuietly(resp);
                if (loc.isEmpty()) {
                    throw new IOException("Redirect with no Location header (HTTP " + status + ")");
                }
                if (hop == MAX_REDIRECTS) {
                    throw new IOException("Too many redirects (>" + MAX_REDIRECTS + ")");
                }
                URI next = current.resolve(loc.get());
                assertHttp(next);
                if (!skipHostGuard) {
                    assertPublicHost(next);
                }
                current = next;
            } else {
                return resp;
            }
        }
        throw new IOException("Too many redirects");
    }

    private static DownloadResult streamToTemp(InputStream body, Path tempFile, int maxBytes)
            throws IOException, SizeLimitExceededException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        long total = 0;
        byte[] buf = new byte[8192];
        try (InputStream src = body;
             OutputStream out = new DigestOutputStream(Files.newOutputStream(tempFile), md)) {
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new SizeLimitExceededException();
                }
                out.write(buf, 0, n);
            }
        }
        return new DownloadResult(hexDigest(md.digest()), total);
    }

    private static ToolResult buildResult(URI uri, Path finalPath, String sha256, long bytes) {
        Source source = new Source(
                uri,
                finalPath.getFileName().toString(),
                Instant.now(),
                sha256,
                Optional.of(finalPath));
        Citation citation = new Citation(source, "", 0, 0);
        String relName = finalPath.getFileName().toString();
        String msg = "Saved " + bytes + " bytes to " + relName + "; sha256=" + sha256;
        return new ToolResult(msg, false, List.of(citation));
    }

    private static boolean isAllowedContentType(String ctBase) {
        for (String prefix : ALLOWED_CT_PREFIXES) {
            if (ctBase.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String lastSegment(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) return "download.bin";
        String[] parts = path.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return "download.bin";
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hexDigest(md.digest());
    }

    private static String hexDigest(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void drainQuietly(HttpResponse<InputStream> resp) {
        try (InputStream s = resp.body()) {
            byte[] buf = new byte[4096];
            while (s.read(buf) > 0) { /* discard */ }
        } catch (IOException ignored) { /* drop */ }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Could not delete temp file {}: {}", path, e.getMessage());
        }
    }

    // --- SSRF guard (local re-implementation; no dependency on web-fetch module) ---

    private static void assertHttp(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new IllegalArgumentException("uri scheme required");
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }
    }

    private static void assertPublicHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("uri host required");
        }
        String stripped = host;
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        String lower = stripped.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw new IllegalArgumentException("private host: " + host);
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(stripped);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown host: " + host, e);
        }
        for (InetAddress addr : addrs) {
            assertPublicAddress(addr, host);
        }
    }

    private static void assertPublicAddress(InetAddress addr, String host) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            throw new IllegalArgumentException(
                    "private host: " + host + " -> " + addr.getHostAddress());
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 169 && b1 == 254) {
                throw new IllegalArgumentException("link-local host: " + host);
            }
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                throw new IllegalArgumentException("CGN host: " + host);
            }
            if (b0 == 0) {
                throw new IllegalArgumentException("reserved host: " + host);
            }
        }
    }

    // --- Schema ---

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> hintProp = new LinkedHashMap<>();
        hintProp.put("type", "string");

        Map<String, Object> maxBytesProp = new LinkedHashMap<>();
        maxBytesProp.put("type", "integer");
        maxBytesProp.put("minimum", SCHEMA_MIN_BYTES);
        maxBytesProp.put("maximum", SCHEMA_MAX_BYTES);
        maxBytesProp.put("default", DEFAULT_MAX_BYTES);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("filename_hint", hintProp);
        properties.put("max_bytes", maxBytesProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }

    private record DownloadResult(String sha256, long bytes) {}

    private static final class SizeLimitExceededException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
