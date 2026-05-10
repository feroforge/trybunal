package org.trybunal.tool.webfetch;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.ToolResult;

class WebFetchToolTest {

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

    @Test
    void returnsExtractedTextWithMatchingSha() throws Exception {
        String html = "<html><head><title>Hello</title></head>"
                + "<body><p>First paragraph here.</p><p>Second paragraph here.</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        route("/page", new StaticHandler("text/html; charset=utf-8", bytes, 200));

        WebFetchTool tool = new WebFetchTool(client, 1024 * 1024, true);
        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/page").toString());

        ToolResult r = tool.invoke(args);
        assertFalse(r.isError(), () -> "got error: " + r.content());
        assertTrue(r.content().contains("First paragraph"));
        assertTrue(r.content().contains("Second paragraph"));
        assertEquals(1, r.citations().size());
        Citation c = r.citations().get(0);
        assertEquals(sha256Hex(bytes), c.source().sha256());
        assertEquals("Hello", c.source().title());
        assertNotNull(c.source().retrievedAt());
    }

    @Test
    void oversizedResponseReturnsError() {
        byte[] big = new byte[2048];
        for (int i = 0; i < big.length; i++) big[i] = 'a';
        // wrap a tiny bit of HTML around it; doesn't matter since we should abort first
        route("/big", new StaticHandler("text/html", big, 200));

        WebFetchTool tool = new WebFetchTool(client, 512, true);
        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/big").toString());

        ToolResult r = tool.invoke(args);
        assertTrue(r.isError());
        assertTrue(r.content().contains("exceeded"));
    }

    @Test
    void nonTextContentTypeRejected() {
        route("/zip", new StaticHandler("application/zip", new byte[]{1, 2, 3}, 200));

        WebFetchTool tool = new WebFetchTool(client, 1024, true);
        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/zip").toString());

        ToolResult r = tool.invoke(args);
        assertTrue(r.isError());
        assertTrue(r.content().contains("non-text"));
        assertTrue(r.content().contains("safe_download"));
    }

    @Test
    void jsHeavyHeuristicTriggers() {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><title>SPA</title>");
        for (int i = 0; i < 6; i++) html.append("<script>var x").append(i).append("=1;</script>");
        html.append("</head><body><div id=\"root\"></div></body></html>");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        route("/spa", new StaticHandler("text/html; charset=utf-8", bytes, 200));

        WebFetchTool tool = new WebFetchTool(client, 1024 * 1024, true);
        Map<String, Object> args = new HashMap<>();
        args.put("url", url("/spa").toString());

        ToolResult r = tool.invoke(args);
        assertFalse(r.isError(), () -> "got error: " + r.content());
        assertTrue(r.content().startsWith("[js-heavy: try web_browser]"),
                () -> "content was: " + r.content());
        assertEquals(1, r.citations().size());
    }

    @Test
    void productionInvokeRejectsLocalhost() {
        // Server is reachable, but production constructor MUST trip the guard.
        route("/page", new StaticHandler("text/html",
                "<html><body><p>x</p></body></html>".getBytes(StandardCharsets.UTF_8), 200));

        WebFetchTool tool = new WebFetchTool(); // production
        Map<String, Object> args = new HashMap<>();
        args.put("url", "http://localhost:" + port + "/page");

        ToolResult r = tool.invoke(args);
        assertTrue(r.isError());
        assertTrue(r.content().toLowerCase().contains("refused"));
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
