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

class SerperSearchProviderTest {

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
    void parsesResultsAndSendsApiKeyHeader() throws Exception {
        AtomicReference<String> capturedKey = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String body = """
                {"organic":[
                  {"title":"X","link":"https://x.example/","snippet":"x-snip"},
                  {"title":"Y","link":"https://y.example/","snippet":"y-snip"}
                ]}
                """;
        server.createContext("/search", (HttpExchange ex) -> {
            capturedKey.set(ex.getRequestHeaders().getFirst("X-API-KEY"));
            capturedBody.set(new String(ex.getRequestBody().readAllBytes()));
            byte[] b = body.getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        SerperSearchProvider provider = new SerperSearchProvider(
                endpoint("/search"), http, json, () -> "serp-key");

        List<SearchHit> hits = provider.search("hello", 2);

        assertEquals(2, hits.size());
        assertEquals("X", hits.get(0).title());
        assertEquals("https://x.example/", hits.get(0).url());
        assertEquals("x-snip", hits.get(0).snippet());

        assertEquals("serp-key", capturedKey.get(), "X-API-KEY header should be set");
        JsonNode parsed = json.readTree(capturedBody.get());
        assertEquals("hello", parsed.path("q").asText());
        assertEquals(2, parsed.path("num").asInt());
    }

    @Test
    void unauthorizedThrowsIOException() throws Exception {
        server.createContext("/search", (HttpExchange ex) -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });

        SerperSearchProvider provider = new SerperSearchProvider(
                endpoint("/search"), http, json, () -> "bad-key");

        assertThrows(IOException.class, () -> provider.search("q", 5));
    }

    @Test
    void notAvailableWithoutKey() {
        SerperSearchProvider provider = new SerperSearchProvider(
                endpoint("/search"), http, json, () -> "");
        assertFalse(provider.isAvailable());
    }
}
