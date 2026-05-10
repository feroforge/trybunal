package org.trybunal.tool.citations;

import java.net.URI;
import java.time.Instant;
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
 * Records a quoted excerpt with attribution into a {@link CitationStore}.
 *
 * <p><b>Contract.</b> Thread-safe; stateless aside from the injected
 * {@link CitationStore}. The public no-arg constructor uses {@link CitationStore#shared()};
 * tests should use the package-private {@code (CitationStore)} constructor.</p>
 */
public final class CiteTool implements Tool {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private static final ToolSpec SPEC = new ToolSpec(
            "cite",
            "Record a quoted excerpt with attribution. Use this BEFORE asserting any factual "
                    + "claim sourced from a fetched page or downloaded file.",
            buildSchema());

    private final CitationStore store;
    private final AtomicInteger counter = new AtomicInteger(0);

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public CiteTool() {
        this(CitationStore.shared());
    }

    /** Package-private constructor for tests — avoids shared-singleton pollution. */
    CiteTool(CitationStore store) {
        this.store = store;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        if (arguments == null) {
            return ToolResult.error("Missing required arguments");
        }

        // url (required, non-blank)
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

        // excerpt (required, non-blank)
        Object excerptArg = arguments.get("excerpt");
        if (excerptArg == null || excerptArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: excerpt");
        }
        String excerpt = excerptArg.toString();

        // sha256 (required, pattern)
        Object sha256Arg = arguments.get("sha256");
        if (sha256Arg == null || sha256Arg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: sha256");
        }
        String sha256 = sha256Arg.toString().strip();
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            return ToolResult.error("sha256 must be 64 lowercase hex characters");
        }

        // title (optional, defaults to "")
        Object titleArg = arguments.get("title");
        String title = (titleArg != null) ? titleArg.toString() : "";

        Source source = new Source(uri, title, Instant.now(), sha256, Optional.empty());
        Citation citation = new Citation(source, excerpt, 0, excerpt.length());
        store.add(citation);
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
