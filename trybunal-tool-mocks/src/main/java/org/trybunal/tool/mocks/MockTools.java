package org.trybunal.tool.mocks;

import java.util.List;
import org.trybunal.api.spi.Tool;

/**
 * Entry point for the mock tool harness.
 *
 * <p><b>Contract.</b> The mocks returned here are NEVER registered via
 * {@link java.util.ServiceLoader}. Callers select them explicitly,
 * typically gated on {@link #enabled()} reading
 * {@code -Dtrybunal.useMocks=true}. A production run that forgets to
 * gate will continue to dispatch real tools — that is the load-bearing
 * decision of this module.</p>
 */
public final class MockTools {

    private MockTools() {}

    /**
     * All five mock tools, in canonical order. Each call returns a
     * fresh list of fresh instances; mocks hold per-instance state
     * (e.g. {@code cite}'s counter) so callers should treat them as
     * one-shot per orchestrator.
     *
     * @return list of length five, ordered:
     *         {@code web_search}, {@code web_fetch}, {@code web_browser},
     *         {@code safe_download}, {@code cite}; never null
     */
    public static List<Tool> all() {
        return List.of(
                new MockWebSearchTool(),
                new MockWebFetchTool(),
                new MockWebBrowserTool(),
                new MockSafeDownloadTool(),
                new MockCiteTool());
    }

    /**
     * Whether {@code -Dtrybunal.useMocks=true} was set on the JVM.
     *
     * @return {@code true} only if the property is present and parses
     *         (case-insensitively) as {@code true}; {@code false}
     *         otherwise (including when unset)
     */
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("trybunal.useMocks", "false"));
    }
}
