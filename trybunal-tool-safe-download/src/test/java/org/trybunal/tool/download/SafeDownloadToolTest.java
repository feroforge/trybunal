package org.trybunal.tool.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.ToolResult;

class SafeDownloadToolTest {

    @TempDir
    Path sandbox;

    private HttpServer server;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void route(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private SafeDownloadTool tool() {
        return new SafeDownloadTool(client, sandbox, true);
    }

    @Test
    void successfulPdfDownload() throws Exception {
        byte[] body = "PDF content bytes".getBytes();
        route("/file.pdf", new StaticHandler("application/pdf", body, 200));

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/file.pdf").toString());
        args.put("filename_hint", "report.pdf");

        ToolResult r = tool().invoke(args);
        assertFalse(r.isError(), () -> "got error: " + r.content());
        assertTrue(r.content().contains("sha256="), "should include sha256");
        assertTrue(r.content().contains("report.pdf"), "should mention saved filename");

        assertEquals(1, r.citations().size());
        Citation c = r.citations().get(0);
        assertNotNull(c.source().localPath());
        assertTrue(c.source().localPath().isPresent(), "localPath should be set");

        Path savedPath = c.source().localPath().get();
        assertTrue(Files.exists(savedPath), "file should exist on disk");
        byte[] savedBytes = Files.readAllBytes(savedPath);
        assertTrue(Arrays.equals(body, savedBytes), "saved content should match");
        assertEquals(sha256Hex(body), c.source().sha256(), "sha256 should match");
    }

    @Test
    void disallowedContentTypeRejected() {
        byte[] body = new byte[]{1, 2, 3};
        route("/flash", new StaticHandler("application/x-shockwave-flash", body, 200));

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/flash").toString());

        ToolResult r = tool().invoke(args);
        assertTrue(r.isError(), "should be error");
        assertTrue(r.content().toLowerCase().contains("disallowed"), "should mention disallowed");
        // no files should be written
        try {
            long count = Files.list(sandbox).count();
            assertEquals(0, count, "no files should be written to sandbox");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void oversizeBodyAborted() throws IOException {
        byte[] big = new byte[4096];
        Arrays.fill(big, (byte) 'x');
        route("/big", new StaticHandler("application/octet-stream", big, 200));

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/big").toString());
        args.put("max_bytes", 1024);

        ToolResult r = tool().invoke(args);
        assertTrue(r.isError(), "should be error");
        assertTrue(r.content().toLowerCase().contains("exceeded"), "should mention exceeded");

        // no temp file left behind
        long count = Files.list(sandbox).count();
        assertEquals(0, count, "no temp file should remain");
    }

    @Test
    void pathTraversalInFilenameHintRejected() {
        byte[] body = "data".getBytes();
        route("/data", new StaticHandler("application/octet-stream", body, 200));

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/data").toString());
        args.put("filename_hint", "../etc/passwd");

        ToolResult r = tool().invoke(args);
        assertTrue(r.isError(), "should be error");
        assertTrue(r.content().toLowerCase().contains("rejected") || r.content().toLowerCase().contains("refused"),
                "should mention rejected: " + r.content());
    }

    @Test
    void ssrfGuardRejectsLoopback() {
        // Use the production constructor (skipHostGuard=false)
        SafeDownloadTool prodTool = new SafeDownloadTool(client, sandbox, false);

        Map<String, Object> args = new HashMap<>();
        args.put("url", "http://127.0.0.1/foo");

        ToolResult r = prodTool.invoke(args);
        assertTrue(r.isError(), "should be error");
        assertTrue(r.content().toLowerCase().contains("refused") || r.content().toLowerCase().contains("private"),
                "should mention refused: " + r.content());
    }

    @Test
    void dedupeDownloadsSameBodyOnce() throws Exception {
        byte[] body = "dedupe content".getBytes();
        route("/same", new StaticHandler("application/pdf", body, 200));

        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/same").toString());
        args.put("filename_hint", "same.pdf");

        ToolResult r1 = tool().invoke(args);
        ToolResult r2 = tool().invoke(args);

        assertFalse(r1.isError(), "first download should succeed");
        assertFalse(r2.isError(), "second download should succeed");

        // Both should point to the same SHA-256
        String sha1 = r1.citations().get(0).source().sha256();
        String sha2 = r2.citations().get(0).source().sha256();
        assertEquals(sha1, sha2, "both downloads should have same sha256");

        // Only one final file (temp files are cleaned up)
        long fileCount = Files.list(sandbox).filter(p -> !p.getFileName().toString().startsWith(".")).count();
        assertEquals(1, fileCount, "sandbox should contain only one file");
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static final class StaticHandler implements HttpHandler {
        private final String contentType;
        private final byte[] body;
        private final int status;

        StaticHandler(String contentType, byte[] body, int status) {
            this.contentType = contentType;
            this.body = body;
            this.status = status;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
