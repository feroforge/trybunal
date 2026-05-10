package org.trybunal.api.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a {@link org.trybunal.api.spi.ModelHarness} captures around every
 * provider invocation. Immutable; safe to log, persist, or fan out.
 *
 * <p>Token counts and {@code finishReason} are nullable when the underlying
 * provider does not report them. {@code latency} is always populated by the
 * harness even when the provider is silent.</p>
 *
 * <p>{@code providerExtras} carries provider-specific signal that does not
 * fit the common surface — e.g. a reasoning-model's {@code thinking} channel,
 * upstream cache hit counters, or routing breadcrumbs. Keys are
 * provider-defined and consumers must tolerate their absence. Always
 * non-null; {@link Map#of()} when nothing was reported.</p>
 *
 * @param modelId          model that produced the result
 * @param startedAt        wall-clock instant the call was dispatched
 * @param latency          measured wall-clock duration of the call
 * @param promptTokens     prompt token count if reported, else null
 * @param completionTokens completion token count if reported, else null
 * @param toolCalls        tool calls requested by the model; never null
 * @param finishReason     provider-native finish reason if reported, else null
 * @param providerExtras   provider-specific extras; never null, defensively copied
 */
public record InvocationMetadata(
        ModelId modelId,
        Instant startedAt,
        Duration latency,
        Integer promptTokens,
        Integer completionTokens,
        List<ToolCall> toolCalls,
        String finishReason,
        Map<String, Object> providerExtras
) {
    public InvocationMetadata {
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (startedAt == null) throw new IllegalArgumentException("startedAt required");
        if (latency == null) throw new IllegalArgumentException("latency required");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        providerExtras = providerExtras == null ? Map.of() : Map.copyOf(providerExtras);
    }

    /**
     * Convenience constructor for the common case where a provider has no
     * extras to report. Delegates to the canonical constructor with an empty
     * {@code providerExtras} map. Kept so existing 7-arg call sites continue
     * to compile after {@code providerExtras} was added.
     */
    public InvocationMetadata(
            ModelId modelId,
            Instant startedAt,
            Duration latency,
            Integer promptTokens,
            Integer completionTokens,
            List<ToolCall> toolCalls,
            String finishReason
    ) {
        this(modelId, startedAt, latency, promptTokens, completionTokens,
                toolCalls, finishReason, Map.of());
    }
}
