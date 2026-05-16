package org.trybunal.tool.mocks;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

import static org.junit.jupiter.api.Assertions.*;

class MockToolsTest {

    private static final String VALID_SHA256 = "a".repeat(64);

    /** {@link MockTools#all()} returns the five mocks in canonical order. */
    @Test
    void allReturnsFiveMocksInCanonicalOrder() {
        List<Tool> tools = MockTools.all();
        assertEquals(5, tools.size());
        assertEquals("web_search",    tools.get(0).spec().name());
        assertEquals("web_fetch",     tools.get(1).spec().name());
        assertEquals("web_browser",   tools.get(2).spec().name());
        assertEquals("safe_download", tools.get(3).spec().name());
        assertEquals("cite",          tools.get(4).spec().name());
    }

    /** {@link MockTools#enabled()} defaults to false when the system property is unset. */
    @Test
    void enabledReadsSystemProperty() {
        String prior = System.getProperty("trybunal.useMocks");
        try {
            System.clearProperty("trybunal.useMocks");
            assertFalse(MockTools.enabled(), "expected false when unset");

            System.setProperty("trybunal.useMocks", "true");
            assertTrue(MockTools.enabled(), "expected true when set to true");

            System.setProperty("trybunal.useMocks", "false");
            assertFalse(MockTools.enabled(), "expected false when set to false");
        } finally {
            if (prior == null) System.clearProperty("trybunal.useMocks");
            else System.setProperty("trybunal.useMocks", prior);
        }
    }

    /** Every mock spec advertises a non-blank [MOCK]-prefixed description. */
    @Test
    void everyMockDescriptionCarriesMockPrefix() {
        for (Tool t : MockTools.all()) {
            assertTrue(t.spec().description().startsWith("[MOCK]"),
                    () -> t.spec().name() + " description missing [MOCK] prefix");
        }
    }

    /** Mock schemas match the real schemas' required keys and property names. */
    @Test
    void schemasMatchRealToolStructure() {
        Map<String, ToolSpec> byName = specsByName();

        assertSchemaShape(byName.get("web_search"),
                List.of("query"),
                List.of("query", "limit", "provider"));
        assertSchemaShape(byName.get("web_fetch"),
                List.of("url"),
                List.of("url", "max_chars"));
        assertSchemaShape(byName.get("web_browser"),
                List.of("url"),
                List.of("url", "wait_selector", "wait_ms", "max_chars"));
        assertSchemaShape(byName.get("safe_download"),
                List.of("url"),
                List.of("url", "filename_hint", "max_bytes"));
        assertSchemaShape(byName.get("cite"),
                List.of("url", "excerpt", "sha256"),
                List.of("url", "title", "excerpt", "sha256"));
    }

    /** Determinism — invoking each mock twice with the same args yields equal content. */
    @Test
    void determinismSameArgsSameContent() {
        Map<String, ToolSpec> specs = specsByName();

        runDeterministic(specs, "web_search",
                Map.of("query", "ticker AAPL"));
        runDeterministic(specs, "web_fetch",
                Map.of("url", "https://www.sec.gov/cgi-bin/browse-edgar?CIK=AAPL"));
        runDeterministic(specs, "web_browser",
                Map.of("url", "https://www.sec.gov/cgi-bin/browse-edgar?CIK=AAPL"));
        runDeterministic(specs, "safe_download",
                Map.of("url", "https://www.example.com/10k.pdf"));
        runDeterministic(specs, "cite",
                Map.of("url", "https://www.example.com",
                       "excerpt", "hello world",
                       "sha256", VALID_SHA256));
    }

    /** web_search hit #1 should be the EDGAR URL — supports the AAPL workflow. */
    @Test
    void searchTopHitIsEdgar() {
        ToolResult r = new MockWebSearchTool().invoke(Map.of("query", "ticker AAPL 10-K"));
        assertFalse(r.isError());
        String[] lines = r.content().split("\n", -1);
        // Line 1 is "[1] title", line 2 is the indented URL.
        assertTrue(lines.length >= 2);
        assertTrue(lines[1].trim().startsWith("https://www.sec.gov/"),
                () -> "expected EDGAR URL on hit #1, got: " + lines[1]);
    }

    /** web_fetch on the EDGAR URL contains a filing date in the body. */
    @Test
    void fetchOfEdgarContainsFilingDate() {
        ToolResult r = new MockWebFetchTool().invoke(Map.of(
                "url", "https://www.sec.gov/cgi-bin/browse-edgar?CIK=AAPL&type=10-K"));
        assertFalse(r.isError());
        assertTrue(r.content().contains("Filed: 2026-02-01"),
                () -> "expected filing date in fetched text, got: " + r.content());
        assertEquals(1, r.citations().size());
    }

    /** safe_download produces a path under build/mock-sandbox/ and a parsable sha256. */
    @Test
    void safeDownloadReturnsSandboxPathAndSha() {
        ToolResult r = new MockSafeDownloadTool().invoke(Map.of(
                "url", "https://www.example.com/10k.pdf"));
        assertFalse(r.isError());
        assertTrue(r.content().contains("build/mock-sandbox/"),
                () -> "expected sandboxed path: " + r.content());
        int idx = r.content().indexOf("sha256=");
        assertTrue(idx > 0, "missing sha256= in: " + r.content());
        String tail = r.content().substring(idx + "sha256=".length()).strip();
        assertTrue(tail.matches("[0-9a-f]{64}"), "bad sha256: " + tail);
    }

    /** cite rejects bad sha256, accepts a valid one, and counts per instance. */
    @Test
    void citeValidatesShaAndNumbersPerInstance() {
        MockCiteTool tool = new MockCiteTool();
        ToolResult bad = tool.invoke(Map.of(
                "url", "https://e.test", "excerpt", "x", "sha256", "not-hex"));
        assertTrue(bad.isError());

        ToolResult ok1 = tool.invoke(Map.of(
                "url", "https://e.test", "excerpt", "first", "sha256", VALID_SHA256));
        ToolResult ok2 = tool.invoke(Map.of(
                "url", "https://e.test", "excerpt", "second", "sha256", VALID_SHA256));
        assertFalse(ok1.isError());
        assertFalse(ok2.isError());
        assertTrue(ok1.content().contains("citation #1"));
        assertTrue(ok2.content().contains("citation #2"));
    }

    /** Missing required args are surfaced as errors, not exceptions. */
    @Test
    void missingRequiredArgsReturnErrors() {
        assertTrue(new MockWebSearchTool().invoke(Map.of()).isError());
        assertTrue(new MockWebFetchTool().invoke(Map.of()).isError());
        assertTrue(new MockWebBrowserTool().invoke(Map.of()).isError());
        assertTrue(new MockSafeDownloadTool().invoke(Map.of()).isError());
        assertTrue(new MockCiteTool().invoke(Map.of()).isError());
    }

    // ---------------------------------------------------------------------

    private static void runDeterministic(Map<String, ToolSpec> specs, String name, Map<String, Object> args) {
        Tool a = freshByName(name);
        Tool b = freshByName(name);
        ToolResult r1 = a.invoke(args);
        ToolResult r2 = b.invoke(args);
        assertEquals(r1.content(), r2.content(),
                () -> name + " was not deterministic between fresh instances");
        assertEquals(r1.isError(), r2.isError());
    }

    private static Tool freshByName(String name) {
        return switch (name) {
            case "web_search"    -> new MockWebSearchTool();
            case "web_fetch"     -> new MockWebFetchTool();
            case "web_browser"   -> new MockWebBrowserTool();
            case "safe_download" -> new MockSafeDownloadTool();
            case "cite"          -> new MockCiteTool();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Map<String, ToolSpec> specsByName() {
        java.util.LinkedHashMap<String, ToolSpec> out = new java.util.LinkedHashMap<>();
        for (Tool t : MockTools.all()) {
            out.put(t.spec().name(), t.spec());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void assertSchemaShape(ToolSpec spec,
                                          List<String> requiredKeys,
                                          List<String> propertyKeys) {
        assertNotNull(spec, "missing tool spec");
        Map<String, Object> schema = spec.jsonSchema();
        assertEquals("object", schema.get("type"));
        List<String> required = (List<String>) schema.get("required");
        assertNotNull(required, () -> spec.name() + " schema missing 'required'");
        assertEquals(requiredKeys, required, () -> spec.name() + " required mismatch");
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props, () -> spec.name() + " schema missing 'properties'");
        assertEquals(propertyKeys, List.copyOf(props.keySet()),
                () -> spec.name() + " property keys mismatch");
    }
}
