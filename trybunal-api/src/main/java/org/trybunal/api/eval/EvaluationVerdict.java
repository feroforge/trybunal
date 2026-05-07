package org.trybunal.api.eval;

import java.util.Map;

/**
 * The outcome produced by an evaluator for a single {@link EvaluationCase}.
 *
 * @param passed       whether the evaluator considers the case a pass
 * @param score        normalized score in {@code [0.0, 1.0]} — {@code 0} fail,
 *                     {@code 1} pass; partial values are allowed for graded
 *                     judges
 * @param evaluatorId  identifier of the evaluator that produced this verdict;
 *                     matches {@code Evaluator.id()}; non-blank
 * @param rationale    human-readable explanation; never null (null is
 *                     normalized to {@code ""})
 * @param details      evaluator-specific extras; defensively copied; never
 *                     null (null is normalized to an empty map)
 */
public record EvaluationVerdict(
        boolean passed,
        double score,
        String evaluatorId,
        String rationale,
        Map<String, Object> details
) {
    public EvaluationVerdict {
        if (evaluatorId == null || evaluatorId.isBlank())
            throw new IllegalArgumentException("evaluatorId required");
        if (Double.isNaN(score) || score < 0.0 || score > 1.0)
            throw new IllegalArgumentException("score must be in [0,1]");
        if (rationale == null) rationale = "";
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
