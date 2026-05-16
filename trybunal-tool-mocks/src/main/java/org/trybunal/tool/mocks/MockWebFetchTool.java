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
 * Mock implementation of {@code web_fetch} — never touches the network.
 *
 * <p><b>Contract.</b> Thread-safe; deterministic on input. The
 * {@link ToolSpec#jsonSchema()} is structurally identical to the real
 * {@code web_fetch} tool (same required keys, same property names,
 * same numeric bounds). Returns a single {@link Citation} sourcing the
 * fixture text to the input URL with a fixed timestamp. No I/O is
 * performed.</p>
 */
public final class MockWebFetchTool implements Tool {

    private static final int SCHEMA_MIN_CHARS = 256;
    private static final int SCHEMA_MAX_CHARS = 200_000;
    private static final int SCHEMA_DEFAULT_CHARS = 20_000;

    /** Fixed timestamp baked into citation Source. Not now(). */
    private static final java.time.Instant FIXED_INSTANT =
            java.time.Instant.parse("2026-02-01T12:00:00Z");

    private static final ToolSpec SPEC = new ToolSpec(
            "web_fetch",
            "[MOCK] Fetch a URL and extract readable text. Returns a deterministic in-process fixture; no network access.",
            buildSchema());

    public MockWebFetchTool() {}

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

        int maxChars = SCHEMA_DEFAULT_CHARS;
        Object mc = arguments.get("max_chars");
        if (mc != null) {
            try {
                maxChars = ((Number) mc).intValue();
            } catch (ClassCastException ignored) {
                try {
                    maxChars = Integer.parseInt(mc.toString());
                } catch (NumberFormatException nfe) {
                    return ToolResult.error("Invalid max_chars: " + mc);
                }
            }
        }
        if (maxChars < SCHEMA_MIN_CHARS) maxChars = SCHEMA_MIN_CHARS;
        if (maxChars > SCHEMA_MAX_CHARS) maxChars = SCHEMA_MAX_CHARS;

        String text = MockFixtures.fetchedText(uri, maxChars);
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

        Map<String, Object> maxCharsProp = new LinkedHashMap<>();
        maxCharsProp.put("type", "integer");
        maxCharsProp.put("minimum", SCHEMA_MIN_CHARS);
        maxCharsProp.put("maximum", SCHEMA_MAX_CHARS);
        maxCharsProp.put("default", SCHEMA_DEFAULT_CHARS);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("max_chars", maxCharsProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }
}
