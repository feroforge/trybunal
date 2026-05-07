package org.trybunal.api.eval;

/**
 * A single evaluation: pair a user prompt with the criterion to grade the
 * resulting model output against.
 *
 * @param name         display name for the case; non-blank
 * @param userMessage  the user message to send to the model; non-null
 *                     (empty is permitted)
 * @param criteria     the criterion the model's reply will be checked against;
 *                     non-null
 */
public record EvaluationCase(
        String name,
        String userMessage,
        EvaluationCriteria criteria
) {
    public EvaluationCase {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (userMessage == null) throw new IllegalArgumentException("userMessage required");
        if (criteria == null) throw new IllegalArgumentException("criteria required");
    }
}
