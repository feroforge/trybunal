package org.trybunal.api.model;

import java.util.List;
import java.util.Map;
import org.trybunal.api.tool.ToolSpec;

/**
 * Provider-agnostic generation knobs.
 *
 * <p>Implementations MUST silently ignore parameters they do not support
 * (e.g. {@code seed} on a backend without deterministic sampling). For
 * provider-specific tuning that does not fit the common surface, callers
 * should use {@link #providerExtras()} — keys are provider-defined.</p>
 *
 * <p>All fields are nullable to mean "use provider default", except
 * {@code providerExtras} and {@code tools} which are always non-null
 * (defaulting to empty collections). Providers that do not support tool
 * calling MUST silently ignore the {@code tools} field.</p>
 */
public record GenerationParams(
        Double temperature,
        Integer maxTokens,
        Double topP,
        Long seed,
        Map<String, Object> providerExtras,
        List<ToolSpec> tools
) {
    public GenerationParams {
        providerExtras = providerExtras == null ? Map.of() : Map.copyOf(providerExtras);
        tools          = tools          == null ? List.of() : List.copyOf(tools);
    }

    /** Sensible defaults for interactive chat use. */
    public static GenerationParams defaults() {
        return new GenerationParams(0.7, -1, null, null, Map.of(), List.of());
    }

    /** Returns a copy of {@code this} with {@code tools} replaced. */
    public GenerationParams withTools(List<ToolSpec> tools) {
        return new GenerationParams(temperature, maxTokens, topP, seed, providerExtras, tools);
    }
}
