package org.trybunal.tool.websearch;

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

class BraveSearchProviderTest {

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
    void parsesThreeResults() throws Exception {
        AtomicReference<String> capturedToken = new AtomicReference<>();
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        String body = """
                {"web":{"results":[
                  {"title":"One","url":"https://example.com/1","description":"snip-1"},
                  {"title":"Two","url":"https://example.com/2","description":"snip-2"},
                  {"title":"Three","url":"https://example.com/3","description":"snip-3"}
                ]}}
                """;
        server.createContext("/search", (HttpExchange ex) -> {
            capturedToken.set(ex.getRequestHeaders().getFirst("X-Subscription-Token"));
            capturedQuery.set(ex.getRequestURI().getQuery());
            byte[] b = body.getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        BraveSearchProvider provider = new BraveSearchProvider(
                endpoint("/search"), http, json, () -> "test-key");

        List<SearchHit> hits = provider.search("hello world", 3);

        assertEquals(3, hits.size());
        assertEquals("One", hits.get(0).title());
        assertEquals("https://example.com/1", hits.get(0).url());
        assertEquals("snip-1", hits.get(0).snippet());
        assertEquals("test-key", capturedToken.get(), "X-Subscription-Token header should be set");
        assertNotNull(capturedQuery.get());
        assertTrue(capturedQuery.get().contains("q=hello+world") || capturedQuery.get().contains("q=hello%20world"),
                "Query string should encode the query: " + capturedQuery.get());
        assertTrue(capturedQuery.get().contains("count=3"), "count param should be set");
    }

    @Test
    void unauthorizedThrowsIOException() throws Exception {
        server.createContext("/search", (HttpExchange ex) -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });

        BraveSearchProvider provider = new BraveSearchProvider(
                endpoint("/search"), http, json, () -> "bad-key");

        assertThrows(IOException.class, () -> provider.search("q", 5));
    }

    @Test
    void notAvailableWithoutKey() {
        BraveSearchProvider provider = new BraveSearchProvider(
                endpoint("/search"), http, json, () -> null);
        assertFalse(provider.isAvailable());

        BraveSearchProvider blank = new BraveSearchProvider(
                endpoint("/search"), http, json, () -> "   ");
        assertFalse(blank.isAvailable());

        BraveSearchProvider set = new BraveSearchProvider(
                endpoint("/search"), http, json, () -> "k");
        assertTrue(set.isAvailable());
    }
}
