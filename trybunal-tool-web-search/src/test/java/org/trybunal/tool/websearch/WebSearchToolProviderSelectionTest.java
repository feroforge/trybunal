package org.trybunal.tool.websearch;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolProviderSelectionTest {

    /** Stub provider that returns a single hit echoing its own id, no I/O. */
    private static final class StubProvider implements SearchProvider {
        private final String id;
        private final boolean available;

        StubProvider(String id, boolean available) {
            this.id = id;
            this.available = available;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<SearchHit> search(String query, int limit) {
            return List.of(new SearchHit("title-" + id, "https://" + id + ".example/", "snip"));
        }
    }

    private static List<SearchProvider> providers(boolean brave, boolean tavily, boolean serper) {
        return List.of(
                new StubProvider("brave", brave),
                new StubProvider("tavily", tavily),
                new StubProvider("serper", serper),
                new StubProvider("duckduckgo", true)
        );
    }

    private static String runAndExtractProvider(WebSearchTool tool) {
        ToolResult r = tool.invoke(Map.of("query", "hi"));
        assertFalse(r.isError(), "Expected ok, got: " + r.content());
        String c = r.content();
        int idx = c.lastIndexOf("(provider: ");
        assertTrue(idx >= 0, "Output should include provider trailer: " + c);
        int end = c.indexOf(')', idx);
        return c.substring(idx + "(provider: ".length(), end);
    }

    @Test
    void defaultsToDuckDuckGoEvenWhenBraveAndTavilyAvailable() {
        WebSearchTool tool = new WebSearchTool(providers(true, true, false), null);
        assertEquals("duckduckgo", runAndExtractProvider(tool));
    }

    @Test
    void defaultsToDuckDuckGoWhenOnlyTavilyAvailable() {
        WebSearchTool tool = new WebSearchTool(providers(false, true, false), null);
        assertEquals("duckduckgo", runAndExtractProvider(tool));
    }

    @Test
    void defaultsToDuckDuckGoWhenNothingConfigured() {
        WebSearchTool tool = new WebSearchTool(providers(false, false, false), null);
        assertEquals("duckduckgo", runAndExtractProvider(tool));
    }

    @Test
    void perCallProviderArgumentOverridesDefault() {
        WebSearchTool tool = new WebSearchTool(providers(true, false, false), null);
        ToolResult r = tool.invoke(Map.of("query", "hi", "provider", "brave"));
        assertFalse(r.isError(), "Expected ok, got: " + r.content());
        assertTrue(r.content().contains("(provider: brave)"), r.content());
    }

    @Test
    void systemPropertyOverridesEvenWhenBraveAvailable() {
        String key = "trybunal.web_search.provider";
        String prior = System.getProperty(key);
        System.setProperty(key, "tavily");
        try {
            WebSearchTool tool = new WebSearchTool(providers(true, true, false), null);
            assertEquals("tavily", runAndExtractProvider(tool));
        } finally {
            if (prior == null) System.clearProperty(key);
            else System.setProperty(key, prior);
        }
    }

    @Test
    void explicitUnavailableProviderReturnsError() {
        WebSearchTool tool = new WebSearchTool(providers(false, false, false), null);
        ToolResult r = tool.invoke(Map.of("query", "hi", "provider", "serper"));
        assertTrue(r.isError(), "Expected error for unavailable provider");
        String msg = r.content().toLowerCase();
        assertTrue(msg.contains("serper"), "Error should mention provider id: " + r.content());
        assertTrue(msg.contains("api key") || msg.contains("missing") || msg.contains("not configured"),
                "Error should mention missing key: " + r.content());
    }
}
