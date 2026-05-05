package org.trybunal.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelHarness;
import org.trybunal.api.spi.ModelProvider;

/**
 * Reference {@link ModelHarness} that measures wall-clock latency, logs the
 * call lifecycle via slf4j (with MDC keys {@code model} and {@code provider}),
 * and reconciles the provider-reported metadata with measured timing.
 *
 * <p>This is the canonical place for cross-cutting concerns. Providers stay
 * dumb; the harness gets smart.</p>
 */
public final class DefaultModelHarness implements ModelHarness {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelHarness.class);

    private final ModelProvider provider;

    public DefaultModelHarness(ModelProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider required");
        this.provider = provider;
    }

    @Override
    public InvocationResult run(List<Message> conversation, ModelId modelId, GenerationParams params) {
        if (conversation == null || conversation.isEmpty())
            throw new IllegalArgumentException("conversation must be non-empty");
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (params == null) params = GenerationParams.defaults();

        MDC.put("provider", provider.id());
        MDC.put("model", modelId.toString());
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        try {
            log.debug("invoke start messages={}", conversation.size());
            InvocationResult raw = provider.invoke(conversation, modelId, params);
            Duration measured = Duration.ofNanos(System.nanoTime() - startNanos);

            InvocationMetadata reconciled = new InvocationMetadata(
                    modelId,
                    startedAt,
                    measured,
                    raw.metadata().promptTokens(),
                    raw.metadata().completionTokens(),
                    raw.metadata().toolCalls(),
                    raw.metadata().finishReason()
            );
            log.debug("invoke ok latencyMs={} finish={}",
                    measured.toMillis(), reconciled.finishReason());
            return new InvocationResult(raw.reply(), reconciled);
        } catch (RuntimeException e) {
            log.warn("invoke failed after {}ms: {}",
                    Duration.ofNanos(System.nanoTime() - startNanos).toMillis(), e.toString());
            throw e;
        } finally {
            MDC.remove("provider");
            MDC.remove("model");
        }
    }
}
