package org.trybunal.tool.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trybunal.api.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WebSearchTool toolFor(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler);
        URI endpoint = URI.create("http://localhost:" + port + path);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        ObjectMapper mapper = new ObjectMapper();
        DuckDuckGoHtmlProvider ddg = new DuckDuckGoHtmlProvider(endpoint, client, mapper, () -> "");
        return new WebSearchTool(List.of(ddg), "duckduckgo");
    }

    @Test
    void parsesThreeResultsAndDecodesRedirectUrls() throws Exception {
        String html = """
                <html><body>
                <div class="result">
                  <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fone">Result One</a>
                  <a class="result__snippet">Snippet one text here.</a>
                </div>
                <div class="result">
                  <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Ftwo">Result Two</a>
                  <a class="result__snippet">Snippet two text here.</a>
                </div>
                <div class="result">
                  <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2Fthree">Result Three</a>
                  <a class="result__snippet">Snippet three text here.</a>
                </div>
                </body></html>
                """;

        WebSearchTool tool = toolFor("/html/", exchange -> {
            byte[] body = html.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        ToolResult result = tool.invoke(Map.of("query", "test query", "limit", 10));

        assertFalse(result.isError(), "Expected success but got error: " + result.content());
        assertTrue(result.content().contains("[1]"), "Should have first result");
        assertTrue(result.content().contains("[2]"), "Should have second result");
        assertTrue(result.content().contains("[3]"), "Should have third result");
        assertTrue(result.content().contains("https://example.com/one"), "URL 1 should be decoded");
        assertTrue(result.content().contains("https://example.com/two"), "URL 2 should be decoded");
        assertTrue(result.content().contains("https://example.com/three"), "URL 3 should be decoded");
        assertTrue(result.content().contains("Result One"));
        assertTrue(result.content().contains("Snippet one text here."));
        assertTrue(result.content().endsWith("(provider: duckduckgo)"),
                "Output should end with provider trailer, got: " + result.content());
    }

    @Test
    void returns503AsError() throws Exception {
        WebSearchTool tool = toolFor("/html/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        ToolResult result = tool.invoke(Map.of("query", "anything"));

        assertTrue(result.isError(), "Expected error for 503 response");
        assertTrue(result.content().contains("503"), "Error message should mention the status code");
    }

    @Test
    void missingQueryReturnsError() {
        // Use the no-arg constructor — no network call will be made
        WebSearchTool tool = new WebSearchTool();

        ToolResult result = tool.invoke(Map.of());

        assertTrue(result.isError(), "Expected error when query is missing");
        assertTrue(result.content().toLowerCase().contains("query"));
    }
}
