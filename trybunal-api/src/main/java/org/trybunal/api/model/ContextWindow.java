package org.trybunal.api.model;

/**
 * Snapshot of how full the model's context window is after a single
 * invocation. {@code num_ctx} is the configured ceiling Ollama loaded
 * the model with — NOT the model's modelcard maximum, which is often
 * much larger. {@code promptTokens} is the actual tokens consumed by
 * the prompt the provider just sent.
 *
 * <p>{@link #headroom()} is the number of tokens still available for
 * the next turn's prompt growth. When a {@link
 * org.trybunal.core.ToolCallingHarness} sees a headroom under the
 * configured threshold it logs a WARN and (Phase 5 task 03) hands
 * the conversation to a {@code ConversationCompactor}.</p>
 *
 * <p>Immutable, pure-data — no wall-clock reads, no randomness. Safe
 * to log, persist, or fan out to multiple consumers.</p>
 *
 * @param promptTokens tokens consumed by the prompt the provider just sent; must be {@code >= 0}
 * @param numCtx       configured context-window ceiling in tokens; must be {@code > 0}
 */
public record ContextWindow(int promptTokens, int numCtx) {
    public ContextWindow {
        if (promptTokens < 0) throw new IllegalArgumentException("promptTokens must be >= 0");
        if (numCtx <= 0)      throw new IllegalArgumentException("numCtx must be > 0");
    }

    /**
     * Number of tokens still available before the next prompt overflows
     * the configured context window. Clamped to {@code >= 0} so a
     * caller never has to special-case the "already over" case.
     */
    public int headroom() {
        return Math.max(0, numCtx - promptTokens);
    }

    /**
     * Fraction of the context window consumed by the current prompt,
     * in {@code [0.0, 1.0]}. Useful for dashboards and threshold checks
     * that want a unitless signal independent of the model's absolute
     * window size.
     */
    public double fillRatio() {
        return Math.min(1.0, (double) promptTokens / (double) numCtx);
    }
}
