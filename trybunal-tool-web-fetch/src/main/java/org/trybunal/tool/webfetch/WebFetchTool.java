package org.trybunal.tool.webfetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Fetches an http/https URL, extracts readable text, and returns it with a
 * single {@link Citation} pointing back to the source.
 *
 * <p>Refuses non-http(s) schemes and any host that resolves to a loopback,
 * link-local, site-local, any-local, or multicast address. Detects JS-heavy
 * pages and prefixes the response with {@code "[js-heavy: try web_browser]"}
 * so the model can decide to fall back.</p>
 *
 * <p>Stateless and thread-safe; the {@link HttpClient} is shared.</p>
 */
public final class WebFetchTool implements Tool {

    private static final String NAME = "web_fetch";
    private static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final int SCHEMA_MIN_CHARS = 256;
    private static final int SCHEMA_MAX_CHARS = 200_000;
    private static final int SCHEMA_DEFAULT_CHARS = 20_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String UA = "trybunal-web-fetch/0.1";
    private static final String ACCEPT = "text/html, text/*, application/xhtml+xml";

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Fetch a URL and extract readable text. Refuses private network "
                    + "targets. Signals when content is JS-rendered so the caller "
                    + "can fall back to web_browser.",
            buildSchema());

    private final HttpClient http;
    private final int maxBytes;
    private final boolean skipHostGuard;

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public WebFetchTool() {
        this(defaultClient(), DEFAULT_MAX_BYTES, false);
    }

    /** Package-private testing constructor (production guard still applied). */
    WebFetchTool(HttpClient http, int maxBytes) {
        this(http, maxBytes, false);
    }

    /** Package-private testing constructor that can bypass the SSRF guard. */
    WebFetchTool(HttpClient http, int maxBytes, boolean skipHostGuardForTests) {
        this.http = http;
        this.maxBytes = maxBytes;
        this.skipHostGuard = skipHostGuardForTests;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
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

        try {
            UrlGuards.assertHttp(uri);
            if (!skipHostGuard) {
                UrlGuards.assertPublicHost(uri);
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Refused: " + e.getMessage());
        }

        int maxChars = SCHEMA_DEFAULT_CHARS;
        Object mc = arguments.get("max_chars");
        if (mc != null) {
            try {
                maxChars = ((Number) mc).intValue();
            } catch (ClassCastException ignored) {
                try {
                    maxChars = Integer.parseInt(mc.toString());
                } catch (NumberFormatException nfe) {
                    return ToolResult.error("Invalid max_chars: " + mc);
                }
            }
        }
        if (maxChars < SCHEMA_MIN_CHARS) maxChars = SCHEMA_MIN_CHARS;
        if (maxChars > SCHEMA_MAX_CHARS) maxChars = SCHEMA_MAX_CHARS;

        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(READ_TIMEOUT)
                .header("Accept", ACCEPT)
                .header("User-Agent", UA)
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
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

        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        String ctNoParams = contentType.split(";", 2)[0].trim();
        if (!(ctNoParams.startsWith("text/") || ctNoParams.startsWith("application/xhtml"))) {
            drainQuietly(resp);
            return ToolResult.error("non-text content-type: " + (contentType.isEmpty() ? "<missing>" : contentType)
                    + "; use safe_download instead");
        }

        byte[] body;
        try {
            body = readLimited(resp.body(), maxBytes);
        } catch (SizeLimitExceededException e) {
            return ToolResult.error("response exceeded " + maxBytes + " bytes");
        } catch (IOException e) {
            return ToolResult.error("Read failed: " + e.getMessage());
        }

        Document doc = Jsoup.parse(new String(body, charsetFrom(contentType)), uri.toString());
        // Snapshot signals that get removed during stripping.
        int scriptCount = doc.getElementsByTag("script").size();
        boolean hasSpaRoot = !doc.select("div#root, div#__next").isEmpty();
        // Strip non-content elements before extraction.
        doc.select("script, style, nav, footer, header[role=banner], noscript").remove();

        String title = doc.title() == null ? "" : doc.title().strip();
        Elements paragraphs = doc.body() == null ? new Elements() : doc.body().select("p, h1, h2, h3, h4, h5, h6, li");
        StringBuilder textBuf = new StringBuilder();
        if (paragraphs.isEmpty() && doc.body() != null) {
            String bodyText = doc.body().text();
            if (bodyText != null) textBuf.append(bodyText);
        } else {
            for (Element p : paragraphs) {
                String t = p.text();
                if (t == null || t.isBlank()) continue;
                if (textBuf.length() > 0) textBuf.append("\n\n");
                textBuf.append(t.strip());
            }
        }
        String extracted = textBuf.toString();

        // JS-heavy heuristic — see spec.
        boolean jsHeavy = isJsHeavy(extracted, paragraphs.size(), hasSpaRoot, scriptCount);

        if (extracted.length() > maxChars) {
            extracted = extracted.substring(0, maxChars);
        }

        String text;
        if (jsHeavy) {
            text = "[js-heavy: try web_browser]\n" + extracted;
        } else {
            text = extracted;
        }

        String sha = sha256Hex(body);
        Source source = new Source(uri, title, Instant.now(), sha, Optional.empty());
        Citation citation = new Citation(source, text, 0, text.length());
        return new ToolResult(text, false, List.of(citation));
    }

    private static boolean isJsHeavy(String extracted, int paragraphCount, boolean hasSpaRoot, int scriptCount) {
        if (extracted.length() >= 200) return false;
        if (paragraphCount >= 3) return false;
        return hasSpaRoot || scriptCount > 5;
    }

    private static java.nio.charset.Charset charsetFrom(String contentType) {
        if (contentType == null) return java.nio.charset.StandardCharsets.UTF_8;
        int idx = contentType.toLowerCase().indexOf("charset=");
        if (idx < 0) return java.nio.charset.StandardCharsets.UTF_8;
        String cs = contentType.substring(idx + 8).trim();
        int sc = cs.indexOf(';');
        if (sc >= 0) cs = cs.substring(0, sc).trim();
        if (cs.startsWith("\"") && cs.endsWith("\"") && cs.length() >= 2) {
            cs = cs.substring(1, cs.length() - 1);
        }
        try {
            return java.nio.charset.Charset.forName(cs);
        } catch (RuntimeException e) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException, SizeLimitExceededException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        try (InputStream src = in) {
            int n;
            while ((n = src.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new SizeLimitExceededException();
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void drainQuietly(HttpResponse<InputStream> resp) {
        try (InputStream s = resp.body()) {
            byte[] buf = new byte[4096];
            while (s.read(buf) > 0) { /* discard */ }
        } catch (IOException ignored) { /* drop */ }
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> maxCharsProp = new LinkedHashMap<>();
        maxCharsProp.put("type", "integer");
        maxCharsProp.put("minimum", SCHEMA_MIN_CHARS);
        maxCharsProp.put("maximum", SCHEMA_MAX_CHARS);
        maxCharsProp.put("default", SCHEMA_DEFAULT_CHARS);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("max_chars", maxCharsProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }

    private static final class SizeLimitExceededException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
