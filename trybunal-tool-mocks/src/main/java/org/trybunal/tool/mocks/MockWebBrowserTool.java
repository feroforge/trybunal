package org.trybunal.tool.mocks;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Mock implementation of {@code web_browser} — never launches a browser.
 *
 * <p><b>Contract.</b> Thread-safe; deterministic on input. The
 * {@link ToolSpec#jsonSchema()} is structurally identical to the real
 * {@code web_browser} tool (same required keys, same property names,
 * same numeric bounds). Returns mock-rendered text via
 * {@link MockFixtures#browserText} along with a single
 * {@link Citation}. No process is spawned and no
 * {@link Thread#sleep(long)} is invoked — the {@code wait_ms} argument
 * is parsed and validated but ignored.</p>
 */
public final class MockWebBrowserTool implements Tool {

    private static final int DEFAULT_WAIT_MS = 2_000;
    private static final int DEFAULT_MAX_CHARS = 40_000;
    private static final int SCHEMA_MIN_CHARS = 256;
    private static final int SCHEMA_MAX_CHARS = 200_000;
    private static final int MAX_WAIT_MS = 30_000;

    /** Fixed timestamp baked into citation Source. Not now(). */
    private static final java.time.Instant FIXED_INSTANT =
            java.time.Instant.parse("2026-02-01T12:00:00Z");

    private static final ToolSpec SPEC = new ToolSpec(
            "web_browser",
            "[MOCK] Render a URL with a headless browser. Returns a deterministic in-process fixture; no browser is launched.",
            buildSchema());

    public MockWebBrowserTool() {}

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        if (arguments == null) {
            return ToolResult.error("Missing required argument: url");
        }
        Object urlArg = arguments.get("url");
        if (urlArg == null || urlArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: url");
        }
        URI uri;
        try {
            uri = URI.create(urlArg.toString().strip());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid url: " + e.getMessage());
        }

        int maxChars = DEFAULT_MAX_CHARS;
        Object mc = arguments.get("max_chars");
        if (mc != null) {
            try {
                maxChars = ((Number) mc).intValue();
            } catch (ClassCastException ignored) {
                try {
                    maxChars = Integer.parseInt(mc.toString());
                } catch (NumberFormatException ignored2) {
                    // tolerate junk; fall back to default
                }
            }
            if (maxChars < SCHEMA_MIN_CHARS) maxChars = SCHEMA_MIN_CHARS;
            if (maxChars > SCHEMA_MAX_CHARS) maxChars = SCHEMA_MAX_CHARS;
        }

        // wait_ms is parsed for schema parity, but never honoured: mocks
        // are NOT allowed to sleep.
        Object wm = arguments.get("wait_ms");
        if (wm != null) {
            try {
                int parsed = ((Number) wm).intValue();
                if (parsed < 0 || parsed > MAX_WAIT_MS) {
                    // silently clamp — matches the real tool's behaviour
                }
            } catch (ClassCastException ignored) {
                try { Integer.parseInt(wm.toString()); } catch (NumberFormatException ignored2) { /* tolerate */ }
            }
        }

        String text = MockFixtures.browserText(uri, maxChars);
        String sha = MockFixtures.sha256Of(text);
        Source source = new Source(uri, mockTitleFor(uri), FIXED_INSTANT, sha, Optional.empty());
        Citation citation = new Citation(source, text, 0, text.length());
        return new ToolResult(text, false, List.of(citation));
    }

    private static String mockTitleFor(URI uri) {
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.contains("sec.gov")) return "EDGAR Filing Index — Apple Inc. (CIK 0000320193)";
        if (host.contains("apple.com")) return "Apple Investor Relations — SEC Filings";
        return "Apple's latest 10-K, the takeaways";
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> waitSelectorProp = new LinkedHashMap<>();
        waitSelectorProp.put("type", "string");

        Map<String, Object> waitMsProp = new LinkedHashMap<>();
        waitMsProp.put("type", "integer");
        waitMsProp.put("minimum", 0);
        waitMsProp.put("maximum", MAX_WAIT_MS);
        waitMsProp.put("default", DEFAULT_WAIT_MS);

        Map<String, Object> maxCharsProp = new LinkedHashMap<>();
        maxCharsProp.put("type", "integer");
        maxCharsProp.put("minimum", SCHEMA_MIN_CHARS);
        maxCharsProp.put("maximum", SCHEMA_MAX_CHARS);
        maxCharsProp.put("default", DEFAULT_MAX_CHARS);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("wait_selector", waitSelectorProp);
        properties.put("wait_ms", waitMsProp);
        properties.put("max_chars", maxCharsProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }
}
