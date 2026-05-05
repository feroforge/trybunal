package org.trybunal.api.spi;

import java.util.List;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

/**
 * The cross-cutting wrapper around a {@link ModelProvider}. A {@code ModelHarness}
 * is the SOLE place where latency measurement, token accounting reconciliation,
 * tool dispatch, and observability hooks are applied.
 *
 * <p>Harnesses are composable: a {@code ToolCallingHarness} can wrap a
 * {@code TimingHarness} which wraps a raw provider call.</p>
 *
 * <p>Implementations MUST be thread-safe.</p>
 */
public interface ModelHarness {

    /**
     * Run a single turn.
     *
     * @return reply + metadata; never null. The harness guarantees
     *         {@link org.trybunal.api.model.InvocationMetadata#latency()} is
     *         populated even when the underlying provider is silent on timing.
     */
    InvocationResult run(List<Message> conversation, ModelId modelId, GenerationParams params);
}
