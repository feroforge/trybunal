package org.trybunal.api.model;

import java.util.Map;

/**
 * Represents a tool invocation requested by an assistant message.
 *
 * <p>{@code id} is provider-assigned and is later echoed back inside a
 * {@link Message.Tool} response so the model can correlate the result.</p>
 *
 * @param id        provider-assigned correlation id; never null
 * @param toolName  name of the tool the model wants to invoke
 * @param arguments tool-specific arguments; defensively copied, never null
 */
public record ToolCall(String id, String toolName, Map<String, Object> arguments) {
    public ToolCall {
        if (id == null) throw new IllegalArgumentException("id required");
        if (toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("toolName required");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
