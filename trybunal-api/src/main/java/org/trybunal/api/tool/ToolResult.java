package org.trybunal.api.tool;

import java.util.List;

/**
 * The outcome of a {@link org.trybunal.api.spi.Tool#invoke(java.util.Map) tool call}.
 *
 * <p>{@code content} is the textual payload that will be inserted into the
 * conversation as a {@link org.trybunal.api.model.Message.Tool} reply. Tools
 * SHOULD keep this concise; the harness does not truncate.</p>
 *
 * <p>If {@code isError} is true the harness still feeds {@code content} back
 * to the model so it can recover, but it logs and counts it separately.</p>
 *
 * @param content    string returned to the model; never null
 * @param isError    true if the tool failed (timeout, refused URL, parse error)
 * @param citations  any sources the tool produced; defensively copied; never null
 */
public record ToolResult(String content, boolean isError, List<Citation> citations) {
    public ToolResult {
        if (content == null) throw new IllegalArgumentException("content required");
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static ToolResult ok(String content) { return new ToolResult(content, false, List.of()); }
    public static ToolResult error(String message) { return new ToolResult(message, true, List.of()); }
}
