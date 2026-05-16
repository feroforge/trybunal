package org.trybunal.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Factory that wraps an {@link Orchestrator} invocation in a regular
 * {@link Tool} so a parent agent can delegate a sub-task the same way it
 * calls {@code web_fetch}.
 *
 * <p>Subagent-as-tool is not a new SPI category; there is no
 * {@code META-INF/services} registration here. The returned object is a
 * plain {@link Tool} (with an {@link AutoCloseable} mix-in so callers can
 * release the inner orchestrator's executor when the parent run finishes).</p>
 *
 * <p><b>Lifecycle.</b> Each call to {@link #asTool} constructs exactly one
 * inner {@link Orchestrator}. Calling {@link Subagent#close()} shuts down
 * that orchestrator's virtual-thread executor. Subsequent {@link Tool#invoke}
 * calls throw {@link IllegalStateException}.</p>
 */
public final class Subagents {

    private static final Logger log = LoggerFactory.getLogger(Subagents.class);

    /** JSON-Schema key under which the parent passes the subtask string. */
    public static final String TASK_ARGUMENT = "task";

    /** Marker substituted with the task argument inside {@code systemPrompt}. */
    public static final String TASK_MARKER = "%TASK%";

    private Subagents() {}

    /**
     * Build a {@link Subagent} that, when invoked, runs an isolated
     * {@link Orchestrator} against {@code spec.model()} with
     * {@code spec.tools()} registered, and returns the subagent's final
     * reply as the tool result.
     *
     * <p>The tool's JSON schema accepts a single required string argument
     * named {@code "task"}. The parent passes whatever description of the
     * subtask it wants; the subagent receives it as the user message and
     * — independently — has any {@code %TASK%} marker in its system prompt
     * substituted with the same string.</p>
     *
     * <p>The returned {@link Subagent} is independently {@link AutoCloseable}.
     * Callers that share an outer orchestrator should close every subagent
     * within the same try-with-resources scope so virtual-thread executors
     * are released deterministically.</p>
     *
     * @param spec     subagent configuration; non-null
     * @param provider the shared {@link ModelProvider} the subagent dispatches
     *                 through (not duplicated per subagent); non-null
     * @return a closeable {@link Subagent}; never null
     */
    public static Subagent asTool(SubagentSpec spec, ModelProvider provider) {
        Objects.requireNonNull(spec, "spec required");
        Objects.requireNonNull(provider, "provider required");
        return new DefaultSubagent(spec, provider);
    }

    private static final class DefaultSubagent implements Subagent {

        private final SubagentSpec spec;
        private final ToolSpec toolSpec;
        private final Orchestrator inner;
        private volatile boolean closed;

        DefaultSubagent(SubagentSpec spec, ModelProvider provider) {
            this.spec = spec;
            this.toolSpec = new ToolSpec(spec.name(), spec.description(), schema());
            this.inner = Orchestrator.of(
                    List.of(provider), List.of(), spec.tools(), spec.maxToolIterations());
        }

        private static Map<String, Object> schema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            TASK_ARGUMENT, Map.of(
                                    "type", "string",
                                    "description", "The subtask for this subagent to perform.")
                    ),
                    "required", List.of(TASK_ARGUMENT)
            );
        }

        @Override
        public ToolSpec spec() {
            return toolSpec;
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments) {
            if (closed) {
                throw new IllegalStateException(
                        "subagent '" + spec.name() + "' is closed");
            }
            Object raw = arguments == null ? null : arguments.get(TASK_ARGUMENT);
            if (raw == null) {
                return ToolResult.error("missing argument: " + TASK_ARGUMENT);
            }
            String task = raw.toString();
            String resolvedPrompt = spec.systemPrompt().replace(TASK_MARKER, task);

            PromptSession session = new PromptSession(
                    null,
                    "subagent-" + spec.name(),
                    resolvedPrompt,
                    Map.of(),
                    List.of(),
                    spec.paramsOverride(),
                    null);

            try {
                InvocationResult result = inner.chat(session, spec.model(), task);
                return ToolResult.ok(result.reply().content());
            } catch (RuntimeException e) {
                log.warn("subagent '{}' failed", spec.name(), e);
                String msg = e.getClass().getSimpleName()
                        + ": " + (e.getMessage() == null ? "(no message)" : e.getMessage());
                return ToolResult.error(msg);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            inner.close();
        }
    }
}
