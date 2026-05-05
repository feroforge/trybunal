package org.trybunal.api.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What a {@link org.trybunal.api.spi.ModelHarness} captures around every
 * provider invocation. Immutable; safe to log, persist, or fan out.
 *
 * <p>Token counts and {@code finishReason} are nullable when the underlying
 * provider does not report them. {@code latency} is always populated by the
 * harness even when the provider is silent.</p>
 *
 * @param modelId          model that produced the result
 * @param startedAt        wall-clock instant the call was dispatched
 * @param latency          measured wall-clock duration of the call
 * @param promptTokens     prompt token count if reported, else null
 * @param completionTokens completion token count if reported, else null
 * @param toolCalls        tool calls requested by the model; never null
 * @param finishReason     provider-native finish reason if reported, else null
 */
public record InvocationMetadata(
        ModelId modelId,
        Instant startedAt,
        Duration latency,
        Integer promptTokens,
        Integer completionTokens,
        List<ToolCall> toolCalls,
        String finishReason
) {
    public InvocationMetadata {
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (startedAt == null) throw new IllegalArgumentException("startedAt required");
        if (latency == null) throw new IllegalArgumentException("latency required");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
