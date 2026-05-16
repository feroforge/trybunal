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
 * Mock implementation of {@code safe_download} — never writes a file or
 * opens a socket.
 *
 * <p><b>Contract.</b> Thread-safe; deterministic on input. The
 * {@link ToolSpec#jsonSchema()} is structurally identical to the real
 * {@code safe_download} tool (same required keys, same property names,
 * same numeric bounds). The returned message resembles the real tool's
 * output line ({@code Saved <bytes> bytes to <path>; sha256=<hash>})
 * so models trained against the real surface parse it identically. The
 * path is a fixed string under {@code build/mock-sandbox/}; nothing is
 * written to disk.</p>
 */
public final class MockSafeDownloadTool implements Tool {

    private static final int DEFAULT_MAX_BYTES = 50 * 1024 * 1024;
    private static final int SCHEMA_MIN_BYTES = 1024;
    private static final int SCHEMA_MAX_BYTES = 100 * 1024 * 1024;

    /** Fixed timestamp baked into citation Source. Not now(). */
    private static final java.time.Instant FIXED_INSTANT =
            java.time.Instant.parse("2026-02-01T12:00:00Z");

    private static final ToolSpec SPEC = new ToolSpec(
            "safe_download",
            "[MOCK] Download a remote file into a sandboxed directory. Returns a deterministic in-process fixture path and SHA-256; no file is written.",
            buildSchema());

    public MockSafeDownloadTool() {}

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

        // Parse max_bytes for schema parity (validated but otherwise unused).
        Object mb = arguments.get("max_bytes");
        if (mb != null) {
            try {
                int parsed = ((Number) mb).intValue();
                if (parsed < SCHEMA_MIN_BYTES || parsed > SCHEMA_MAX_BYTES) {
                    // silently clamp — matches the real tool's behaviour
                }
            } catch (ClassCastException ignored) {
                try {
                    Integer.parseInt(mb.toString());
                } catch (NumberFormatException nfe) {
                    return ToolResult.error("Invalid max_bytes: " + mb);
                }
            }
        }

        Object hintArg = arguments.get("filename_hint");
        String hint = (hintArg != null && !hintArg.toString().isBlank())
                ? hintArg.toString().strip()
                : null;

        String message = MockFixtures.savedFile(uri, hint);
        String sha = extractSha(message);
        String name = extractFilename(message);
        Source source = new Source(uri, name, FIXED_INSTANT, sha,
                Optional.of(java.nio.file.Path.of("build", "mock-sandbox", name)));
        Citation citation = new Citation(source, "", 0, 0);
        return new ToolResult(message, false, List.of(citation));
    }

    private static String extractSha(String savedLine) {
        int i = savedLine.indexOf("sha256=");
        if (i < 0) return MockFixtures.sha256Of(savedLine);
        return savedLine.substring(i + "sha256=".length()).strip();
    }

    private static String extractFilename(String savedLine) {
        int to = savedLine.indexOf("; sha256=");
        if (to < 0) return "mock.bin";
        int from = savedLine.lastIndexOf('/', to);
        if (from < 0) return "mock.bin";
        return savedLine.substring(from + 1, to);
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> hintProp = new LinkedHashMap<>();
        hintProp.put("type", "string");

        Map<String, Object> maxBytesProp = new LinkedHashMap<>();
        maxBytesProp.put("type", "integer");
        maxBytesProp.put("minimum", SCHEMA_MIN_BYTES);
        maxBytesProp.put("maximum", SCHEMA_MAX_BYTES);
        maxBytesProp.put("default", DEFAULT_MAX_BYTES);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("filename_hint", hintProp);
        properties.put("max_bytes", maxBytesProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }
}
