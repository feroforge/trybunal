package org.trybunal.tool.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Always-available, keyless {@link SearchProvider} that scrapes
 * {@code https://html.duckduckgo.com/html/}. Preserves the exact selectors and
 * {@code /l/?uddg=…} redirect decoding from Task 05.
 */
final class DuckDuckGoHtmlProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoHtmlProvider.class);
    private static final URI DEFAULT_ENDPOINT = URI.create("https://html.duckduckgo.com/html/");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;
    private final HttpClient http;

    DuckDuckGoHtmlProvider(HttpClient http, ObjectMapper json) {
        this(DEFAULT_ENDPOINT, http, json, () -> "");
    }

    /** Test-friendly constructor; {@code json} and {@code apiKey} are unused. */
    DuckDuckGoHtmlProvider(URI endpoint, HttpClient http, ObjectMapper json, Supplier<String> apiKey) {
        this.endpoint = endpoint;
        this.http = http;
    }

    @Override
    public String id() {
        return "duckduckgo";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<SearchHit> search(String query, int limit) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI requestUri = URI.create(endpoint.toString() + "?q=" + encoded);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .GET()
                .header("User-Agent", "trybunal-web-search/0.2")
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
            throw new IOException("Search request failed with status: " + response.statusCode());
        }

        return parseResults(response.body(), limit);
    }

    private static List<SearchHit> parseResults(String html, int limit) {
        Document doc = Jsoup.parse(html);
        Elements resultDivs = doc.select("div.result");
        List<SearchHit> out = new ArrayList<>();
        for (Element div : resultDivs) {
            if (out.size() >= limit) break;
            Element titleEl = div.selectFirst("a.result__a");
            if (titleEl == null) continue;
            String title = titleEl.text().strip();
            String url = decodeRedirectUrl(titleEl.attr("href"));

            Element snippetEl = div.selectFirst("a.result__snippet");
            String snippet = snippetEl != null ? snippetEl.text().strip() : "";

            if (!title.isBlank() && !url.isBlank()) {
                out.add(new SearchHit(title, url, snippet));
            }
        }
        return out;
    }

    private static String decodeRedirectUrl(String href) {
        if (href == null || href.isBlank()) return "";
        if (href.startsWith("/l/") || href.contains("uddg=")) {
            URI uri;
            try {
                uri = URI.create("https://duckduckgo.com" + href);
            } catch (IllegalArgumentException e) {
                return href;
            }
            String query = uri.getRawQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("uddg=")) {
                        return URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return href;
    }
}
