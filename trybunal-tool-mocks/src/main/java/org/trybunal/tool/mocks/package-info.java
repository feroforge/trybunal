/**
 * Opt-in, in-process mock implementations of the Phase 4 toolset
 * ({@code web_search}, {@code web_fetch}, {@code web_browser},
 * {@code safe_download}, {@code cite}). Mocks are deterministic, never
 * touch the network, and never sleep or read external state. They exist
 * so the orchestrator, the upcoming compaction logic, and the
 * subagent-as-tool wrapper can be exercised against a stable fixture
 * surface — and so eval regressions can be attributed to the model
 * rather than to flaky external dependencies (DuckDuckGo 403s, SEC
 * EDGAR throttling, IR sites behind Cloudflare).
 *
 * <p><b>Opt-in only.</b> The mocks in this package are NOT registered
 * via {@code META-INF/services/org.trybunal.api.spi.Tool}. They will
 * never be auto-discovered by {@link java.util.ServiceLoader}, even if
 * this module ends up on the runtime classpath of a production
 * application. Callers select them explicitly by passing
 * {@link org.trybunal.tool.mocks.MockTools#all()} to
 * {@code Orchestrator.of(...)}, typically gated on
 * {@link org.trybunal.tool.mocks.MockTools#enabled()} reading
 * {@code -Dtrybunal.useMocks=true}.</p>
 *
 * <p>Each mock advertises a {@link org.trybunal.api.tool.ToolSpec} with
 * the same {@code name} and structurally-equal {@code jsonSchema} as
 * the corresponding real tool, so a model trained against the real
 * tool surface dispatches without rewriting prompts. The
 * {@code description} field is allowed to differ — it carries a
 * {@code [MOCK]} prefix.</p>
 *
 * <p>Module dependencies: {@code :trybunal-api} only.</p>
 */
package org.trybunal.tool.mocks;
