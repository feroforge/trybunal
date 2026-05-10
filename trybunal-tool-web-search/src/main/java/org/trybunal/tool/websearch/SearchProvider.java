package org.trybunal.tool.websearch;

import java.io.IOException;
import java.util.List;

/**
 * Strategy behind {@link WebSearchTool}. Each implementation maps the tool's
 * {@code (query, limit)} into a backend-specific HTTP call and returns a
 * normalized list of {@link SearchHit}s.
 *
 * <p>Package-private on purpose: this is an internal extension point, not a
 * published SPI. Do not register implementations under
 * {@code META-INF/services/} — the only SPI this module exposes is
 * {@link org.trybunal.api.spi.Tool}.</p>
 */
interface SearchProvider {

    /** Stable short id (e.g. {@code "brave"}) used by the dispatcher and config. */
    String id();

    /**
     * Whether this provider is currently usable (key configured, etc.).
     * Read lazily so env-var changes take effect without rebuilding.
     */
    boolean isAvailable();

    /**
     * Run the search. Implementations should throw on transport errors and
     * non-2xx responses; the dispatcher converts those to
     * {@link org.trybunal.api.tool.ToolResult#error(String)}.
     */
    List<SearchHit> search(String query, int limit) throws IOException, InterruptedException;
}
