package org.trybunal.tool.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TavilySearchProviderTest {

    private HttpServer server;
    private int port;
    private HttpClient http;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI endpoint(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void parsesResultsAndPostsExpectedJson() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String body = """
                {"results":[
                  {"title":"A","url":"https://a.example/","content":"alpha"},
                  {"title":"B","url":"https://b.example/","content":"beta"}
                ]}
                """;
        server.createContext("/search", (HttpExchange ex) -> {
            byte[] req = ex.getRequestBody().readAllBytes();
            capturedBody.set(new String(req));
            byte[] b = body.getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        TavilySearchProvider provider = new TavilySearchProvider(
                endpoint("/search"), http, json, () -> "tav-key");

        List<SearchHit> hits = provider.search("the question", 2);

        assertEquals(2, hits.size());
        assertEquals("A", hits.get(0).title());
        assertEquals("https://a.example/", hits.get(0).url());
        assertEquals("alpha", hits.get(0).snippet());

        assertNotNull(capturedBody.get());
        JsonNode parsed = json.readTree(capturedBody.get());
        assertEquals("tav-key", parsed.path("api_key").asText());
        assertEquals("the question", parsed.path("query").asText());
        assertEquals(2, parsed.path("max_results").asInt());
    }

    @Test
    void unauthorizedThrowsIOException() throws Exception {
        server.createContext("/search", (HttpExchange ex) -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });

        TavilySearchProvider provider = new TavilySearchProvider(
                endpoint("/search"), http, json, () -> "bad-key");

        assertThrows(IOException.class, () -> provider.search("q", 5));
    }

    @Test
    void notAvailableWithoutKey() {
        TavilySearchProvider provider = new TavilySearchProvider(
                endpoint("/search"), http, json, () -> null);
        assertFalse(provider.isAvailable());
    }
}
