package org.trybunal.core;

import org.trybunal.api.spi.Tool;

/**
 * A {@link Tool} that delegates to an isolated {@link Orchestrator} when
 * invoked, plus an {@link AutoCloseable} hook for releasing the inner
 * orchestrator's executor.
 *
 * <p><b>Contract.</b> {@code Subagent} is not a new SPI category — it is a
 * marker that combines {@link Tool} and {@link AutoCloseable} so callers
 * can manage the subagent's lifecycle in a try-with-resources block.
 * Implementations are produced by {@link Subagents#asTool}; the only
 * supported lifecycle is construction → repeated {@link #invoke} →
 * {@link #close()}. Invocation after {@code close()} MUST throw
 * {@link IllegalStateException}.</p>
 *
 * <p>{@link #close()} is declared without a checked exception so call sites
 * inside try-with-resources do not have to surface a checked failure that
 * has nowhere to go.</p>
 */
public interface Subagent extends Tool, AutoCloseable {

    @Override
    void close();
}
