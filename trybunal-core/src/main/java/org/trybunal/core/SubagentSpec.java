package org.trybunal.core;

import java.util.List;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolSpec;

/**
 * Declarative configuration for a {@link Subagents#asTool} call.
 *
 * <p>Captures everything the factory needs to wire up an isolated
 * {@link Orchestrator} that the parent model can invoke through a single
 * {@link Tool} surface. The {@code name} and {@code description} appear in
 * the parent's tool catalogue; {@code model}, {@code tools},
 * {@code systemPrompt}, {@code maxToolIterations}, and
 * {@code paramsOverride} configure the subagent's own ReAct loop.</p>
 *
 * <p>Use the literal marker {@code %TASK%} in {@code systemPrompt} to
 * substitute the task argument the parent passes at invocation time. The
 * substitution is plain {@link String#replace(CharSequence, CharSequence)} —
 * not a regex — so any other text is preserved verbatim.</p>
 *
 * @param name              the {@link ToolSpec#name()} the parent will see;
 *                          non-blank; matches {@code [a-zA-Z0-9_\-]{1,64}}
 * @param description       what this subagent does, in the parent's
 *                          perspective; non-blank
 * @param model             the model the subagent runs against; non-null
 * @param tools             tools the SUBAGENT may call; defensively copied;
 *                          non-null (empty list is permitted)
 * @param systemPrompt      subagent's system prompt; non-null; may contain
 *                          {@code %TASK%} for task substitution
 * @param maxToolIterations subagent's ReAct cap; must be {@code >= 1}
 * @param paramsOverride    generation params for the subagent; {@code null}
 *                          means use {@link GenerationParams#defaults()}
 */
public record SubagentSpec(
        String name,
        String description,
        ModelId model,
        List<Tool> tools,
        String systemPrompt,
        int maxToolIterations,
        GenerationParams paramsOverride
) {
    public SubagentSpec {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name required");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("description required");
        if (model == null)
            throw new IllegalArgumentException("model required");
        if (systemPrompt == null)
            throw new IllegalArgumentException("systemPrompt required");
        if (maxToolIterations < 1)
            throw new IllegalArgumentException("maxToolIterations must be >= 1");
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
