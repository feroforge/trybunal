package org.trybunal.api.model;

import java.util.Map;

/**
 * Provider-agnostic generation knobs.
 *
 * <p>Implementations MUST silently ignore parameters they do not support
 * (e.g. {@code seed} on a backend without deterministic sampling). For
 * provider-specific tuning that does not fit the common surface, callers
 * should use {@link #providerExtras()} — keys are provider-defined.</p>
 *
 * <p>All fields are nullable to mean "use provider default".</p>
 */
public record GenerationParams(
        Double temperature,
        Integer maxTokens,
        Double topP,
        Long seed,
        Map<String, Object> providerExtras
) {
    public GenerationParams {
        providerExtras = providerExtras == null ? Map.of() : Map.copyOf(providerExtras);
    }

    /** Sensible defaults for interactive chat use. */
    public static GenerationParams defaults() {
        return new GenerationParams(0.7, -1, null, null, Map.of());
    }
}
