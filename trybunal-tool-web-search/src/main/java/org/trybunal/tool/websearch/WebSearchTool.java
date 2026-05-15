package org.trybunal.tool.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * A {@link Tool} that performs web searches via a pluggable {@link SearchProvider}.
 *
 * <p>Registered via {@code META-INF/services/org.trybunal.api.spi.Tool}. Stateless and
 * thread-safe; the {@link HttpClient} and {@link ObjectMapper} are created once and
 * shared across all providers and invocations.</p>
 */
public final class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final int SNIPPET_MAX = 240;

    private static final String SYS_PROP = "trybunal.web_search.provider";
    private static final String ENV_VAR = "TRYBUNAL_WEB_SEARCH_PROVIDER";
    private static final String DEFAULT_PROVIDER = "duckduckgo";

    private static final ToolSpec SPEC = new ToolSpec(
            "web_search",
            "Search the web. Returns ranked {title, url, snippet} entries from the configured provider.",
            buildSchema()
    );

    private final List<SearchProvider> providers;
    private final String defaultProviderId;
    private final AtomicBoolean defaultLogged = new AtomicBoolean(false);

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public WebSearchTool() {
        this(buildDefaultProviders(), null);
    }

    /** Package-private testing constructor. */
    WebSearchTool(List<SearchProvider> providers, String defaultProviderId) {
        this.providers = List.copyOf(providers);
        this.defaultProviderId = defaultProviderId != null
                ? defaultProviderId
                : resolveDefault(this.providers);
    }

    private static List<SearchProvider> buildDefaultProviders() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper json = new ObjectMapper();
        return List.of(
                new BraveSearchProvider(http, json),
                new TavilySearchProvider(http, json),
                new SerperSearchProvider(http, json),
                new DuckDuckGoHtmlProvider(http, json)
        );
    }

    private static String resolveDefault(List<SearchProvider> providers) {
        // DuckDuckGo is the default. The system property, env var, or per-call
        // `provider` argument can override it to pick a different engine.
        String fromProp = System.getProperty(SYS_PROP);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.strip();
        }
        String fromEnv = System.getenv(ENV_VAR);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        return DEFAULT_PROVIDER;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        if (defaultLogged.compareAndSet(false, true)) {
            log.info("web_search default provider: {}", defaultProviderId);
        }

        Object queryArg = arguments.get("query");
        if (queryArg == null || queryArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: query");
        }
        String query = queryArg.toString().strip();

        int limit = DEFAULT_LIMIT;
        Object limitArg = arguments.get("limit");
        if (limitArg != null) {
            try {
                limit = ((Number) limitArg).intValue();
            } catch (ClassCastException e) {
                try {
                    limit = Integer.parseInt(limitArg.toString());
                } catch (NumberFormatException nfe) {
                    return ToolResult.error("Invalid limit: " + limitArg);
                }
            }
        }
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));

        String requestedId = defaultProviderId;
        Object providerArg = arguments.get("provider");
        if (providerArg != null) {
            String s = providerArg.toString().strip();
            if (!s.isEmpty() && !s.equals("auto")) {
                requestedId = s;
            }
        }

        SearchProvider provider = null;
        for (SearchProvider p : providers) {
            if (p.id().equals(requestedId)) {
                provider = p;
                break;
            }
        }
        if (provider == null) {
            return ToolResult.error("Unknown provider: " + requestedId);
        }
        if (!provider.isAvailable()) {
            return ToolResult.error("Provider " + provider.id() + " not configured (missing API key)");
        }

        List<SearchHit> hits;
        try {
            hits = provider.search(query, limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("Search failed: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            return ToolResult.ok("No results found.\n(provider: " + provider.id() + ")");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit h = hits.get(i);
            String snippet = h.snippet().length() > SNIPPET_MAX
                    ? h.snippet().substring(0, SNIPPET_MAX)
                    : h.snippet();
            sb.append('[').append(i + 1).append("] ").append(h.title()).append('\n');
            sb.append("    ").append(h.url()).append('\n');
            sb.append("    ").append(snippet).append('\n');
        }
        sb.append("(provider: ").append(provider.id()).append(')');
        return ToolResult.ok(sb.toString());
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("minimum", 1);
        limitProp.put("maximum", 20);
        limitProp.put("default", 5);

        Map<String, Object> providerProp = new LinkedHashMap<>();
        providerProp.put("type", "string");
        providerProp.put("enum", List.of("auto", "duckduckgo", "brave", "tavily", "serper"));
        providerProp.put("default", "auto");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProp);
        properties.put("limit", limitProp);
        properties.put("provider", providerProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }
}
