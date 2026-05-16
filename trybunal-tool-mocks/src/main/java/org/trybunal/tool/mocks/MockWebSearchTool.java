package org.trybunal.tool.mocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Mock implementation of {@code web_search} — never touches the network.
 *
 * <p><b>Contract.</b> Thread-safe; deterministic on input. The
 * {@link ToolSpec#jsonSchema()} is structurally identical to the real
 * {@code web_search} tool (same required keys, same property names,
 * same allowed providers); only the description differs (carries the
 * {@code [MOCK]} prefix). Failures are returned as
 * {@link ToolResult#error}.</p>
 */
public final class MockWebSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private static final ToolSpec SPEC = new ToolSpec(
            "web_search",
            "[MOCK] Search the web. Returns ranked {title, url, snippet} entries from a deterministic in-process fixture.",
            buildSchema());

    public MockWebSearchTool() {}

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        if (arguments == null) {
            return ToolResult.error("Missing required argument: query");
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

        return ToolResult.ok(MockFixtures.searchResults(query, limit));
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
