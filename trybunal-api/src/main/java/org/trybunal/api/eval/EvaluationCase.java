package org.trybunal.api.eval;

import org.trybunal.api.model.GenerationParams;

/**
 * A single evaluation: pair a user prompt with the criterion to grade the
 * resulting model output against.
 *
 * @param name             display name for the case; non-blank
 * @param userMessage      the user message to send to the model; non-null
 *                         (empty is permitted)
 * @param criteria         the criterion the model's reply will be checked against;
 *                         non-null
 * @param paramsOverride   optional per-case generation params; when non-null,
 *                         the orchestrator uses these instead of
 *                         {@code session.params()} for this case only. Right
 *                         knob for "this case needs a much higher
 *                         {@code maxTokens}" without raising it for every
 *                         case in the suite. Nullable.
 */
public record EvaluationCase(
        String name,
        String userMessage,
        EvaluationCriteria criteria,
        GenerationParams paramsOverride
) {
    public EvaluationCase {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (userMessage == null) throw new IllegalArgumentException("userMessage required");
        if (criteria == null) throw new IllegalArgumentException("criteria required");
        // paramsOverride may be null — meaning "fall back to session params".
    }

    /**
     * Convenience constructor for the common case where no per-case params
     * override is needed. Equivalent to passing {@code null} for
     * {@code paramsOverride}.
     */
    public EvaluationCase(String name, String userMessage, EvaluationCriteria criteria) {
        this(name, userMessage, criteria, null);
    }

    /** Returns a copy of this case with {@code paramsOverride} replaced. */
    public EvaluationCase withParamsOverride(GenerationParams paramsOverride) {
        return new EvaluationCase(name, userMessage, criteria, paramsOverride);
    }
}
