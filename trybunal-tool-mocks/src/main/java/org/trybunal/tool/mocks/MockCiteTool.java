package org.trybunal.tool.mocks;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Mock implementation of {@code cite} — no shared state, no store.
 *
 * <p><b>Contract.</b> Thread-safe; deterministic on input. The
 * {@link ToolSpec#jsonSchema()} is structurally identical to the real
 * {@code cite} tool (same required keys, same property names, same
 * SHA-256 pattern). The mock does NOT persist citations to any shared
 * {@code CitationStore} — by design, it has no dependency on
 * {@code trybunal-tool-citations}. Tests that need to observe stored
 * citations should inject the real {@code CiteTool} bound to a
 * test-scoped store; the mock exists for the model-side surface.</p>
 *
 * <p>An internal {@link AtomicInteger} numbers recorded citations
 * per-instance so the model sees a {@code "recorded citation #N for
 * <url>"} message — same shape as the real tool, but the counter
 * resets per-instance and is not globally observable.</p>
 */
public final class MockCiteTool implements Tool {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /** Fixed timestamp baked into citation Source. Not now(). */
    private static final java.time.Instant FIXED_INSTANT =
            java.time.Instant.parse("2026-02-01T12:00:00Z");

    private static final ToolSpec SPEC = new ToolSpec(
            "cite",
            "[MOCK] Record a quoted excerpt with attribution. Validates inputs identically to the real tool; does not persist to any shared store.",
            buildSchema());

    private final AtomicInteger counter = new AtomicInteger(0);

    public MockCiteTool() {}

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        if (arguments == null) {
            return ToolResult.error("Missing required arguments");
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

        Object excerptArg = arguments.get("excerpt");
        if (excerptArg == null || excerptArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: excerpt");
        }
        String excerpt = excerptArg.toString();

        Object sha256Arg = arguments.get("sha256");
        if (sha256Arg == null || sha256Arg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: sha256");
        }
        String sha256 = sha256Arg.toString().strip();
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            return ToolResult.error("sha256 must be 64 lowercase hex characters");
        }

        Object titleArg = arguments.get("title");
        String title = (titleArg != null) ? titleArg.toString() : "";

        Source source = new Source(uri, title, FIXED_INSTANT, sha256, Optional.empty());
        Citation citation = new Citation(source, excerpt, 0, excerpt.length());
        int index = counter.incrementAndGet();

        return new ToolResult(
                "recorded citation #" + index + " for " + uri,
                false,
                List.of(citation));
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> titleProp = new LinkedHashMap<>();
        titleProp.put("type", "string");

        Map<String, Object> excerptProp = new LinkedHashMap<>();
        excerptProp.put("type", "string");

        Map<String, Object> sha256Prop = new LinkedHashMap<>();
        sha256Prop.put("type", "string");
        sha256Prop.put("pattern", "^[0-9a-f]{64}$");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("title", titleProp);
        properties.put("excerpt", excerptProp);
        properties.put("sha256", sha256Prop);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url", "excerpt", "sha256"));
        return schema;
    }
}
