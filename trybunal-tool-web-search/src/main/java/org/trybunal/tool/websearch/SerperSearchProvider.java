package org.trybunal.tool.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serper.dev (Google) search API provider. Reads {@code SERPER_API_KEY} from
 * the env.
 */
final class SerperSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SerperSearchProvider.class);
    private static final URI DEFAULT_ENDPOINT = URI.create("https://google.serper.dev/search");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Supplier<String> apiKey;

    SerperSearchProvider(HttpClient http, ObjectMapper json) {
        this(DEFAULT_ENDPOINT, http, json, () -> System.getenv("SERPER_API_KEY"));
    }

    SerperSearchProvider(URI endpoint, HttpClient http, ObjectMapper json, Supplier<String> apiKey) {
        this.endpoint = endpoint;
        this.http = http;
        this.json = json;
        this.apiKey = apiKey;
    }

    @Override
    public String id() {
        return "serper";
    }

    @Override
    public boolean isAvailable() {
        String k = apiKey.get();
        return k != null && !k.isBlank();
    }

    @Override
    public List<SearchHit> search(String query, int limit) throws IOException, InterruptedException {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new IOException("SERPER_API_KEY not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("q", query);
        body.put("num", limit);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .header("User-Agent", "trybunal-web-search/0.2")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-API-KEY", key)
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            log.warn("Transport error from provider {}: {}", id(), e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Non-2xx response {} from provider {}", response.statusCode(), id());
            throw new IOException("Serper search failed with status: " + response.statusCode());
        }

        JsonNode root = json.readTree(response.body());
        JsonNode results = root.path("organic");
        List<SearchHit> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode r : results) {
                if (out.size() >= limit) break;
                String title = r.path("title").asText("").strip();
                String url = r.path("link").asText("").strip();
                String snippet = r.path("snippet").asText("").strip();
                if (!title.isBlank() && !url.isBlank()) {
                    out.add(new SearchHit(title, url, snippet));
                }
            }
        }
        return out;
    }
}
