package org.trybunal.api.spi;

import org.trybunal.api.model.ContextWindow;

/**
 * Strategy for shrinking a conversation that's about to overflow the
 * model's context window. The harness invokes this before issuing a
 * provider call when {@link ContextWindow#headroom() headroom} falls
 * below a configurable threshold.
 *
 * <p><b>Contract.</b> Implementations MUST be thread-safe (the
 * orchestrator dispatches via virtual threads). Implementations MUST
 * NOT modify the input list. The system + first user message of the
 * conversation MUST be preserved verbatim. The most recent
 * assistant turn and the most recent tool message that pairs with it
 * MUST be preserved verbatim. Anything in between is fair game.</p>
 *
 * <p>Implementations MUST NOT call any model. (No "summarise via LLM"
 * first pass — that's a future concern.) Implementations should be
 * idempotent: running them twice on the same conversation must
 * produce the same output as running once.</p>
 *
 * <p>Discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/org.trybunal.api.spi.ConversationCompactor}.
 * When multiple implementations are registered, the orchestrator selects
 * by {@code -Dtrybunal.compactor=<id>} when set, otherwise the first
 * registered implementation wins.</p>
 */
public interface ConversationCompactor {

    /** Stable id, e.g. {@code "tool-results"}. Used for selection via system property. Never null or blank. */
    String id();

    /**
     * Returns a possibly-shrunken conversation suitable for resending
     * to the provider. May return the input unchanged when no
     * compaction is needed.
     *
     * @param request compaction request; never null
     * @return result; never null. {@code messagesRewritten=0} and
     *         {@code messagesDropped=0} when no change was made.
     */
    CompactionResult compact(CompactionRequest request);
}
