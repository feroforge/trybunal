package org.trybunal.api.spi;

import java.util.Map;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * SPI implemented by every tool the agent loop can dispatch.
 *
 * <p><b>Contract.</b> Implementations MUST be thread-safe; the orchestrator
 * may dispatch them concurrently from virtual threads. Implementations MUST
 * NOT add cross-cutting concerns (timing, request-lifecycle slf4j logging at
 * INFO, retries) — those belong in the harness.</p>
 *
 * <p>Implementations MUST register themselves under
 * {@code META-INF/services/org.trybunal.api.spi.Tool} so the orchestrator can
 * discover them via {@link java.util.ServiceLoader}.</p>
 *
 * <p>{@link ToolSpec#name()} is the routing key — it MUST be unique across all
 * registered tools in a single orchestrator. The orchestrator throws on
 * collision at construction time.</p>
 */
public interface Tool {
    /** Stable advertisement to the model. Identical across calls. */
    ToolSpec spec();

    /**
     * Run the tool with {@code arguments} (already validated against the
     * spec's schema by the harness — implementations may still defend).
     *
     * @param arguments parsed argument map; never null
     * @return result; never null. Failures are returned as {@link ToolResult#error},
     *         not thrown.
     */
    ToolResult invoke(Map<String, Object> arguments);
}
