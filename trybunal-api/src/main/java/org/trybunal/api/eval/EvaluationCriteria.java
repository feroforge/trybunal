package org.trybunal.api.eval;

import org.trybunal.api.model.ModelId;

/**
 * The criterion that an {@link EvaluationCase} is checked against.
 *
 * <p>Sealed over two families:</p>
 * <ul>
 *   <li>{@link TextMatch} — code-based assertions over the model's output text.
 *       Itself sealed; concrete kinds (Contains, Equals, Regex, ...) are added
 *       in later tasks.</li>
 *   <li>{@link LlmRubric} — grade the output with another model against a
 *       free-text rubric.</li>
 * </ul>
 */
public sealed interface EvaluationCriteria
        permits EvaluationCriteria.TextMatch, EvaluationCriteria.LlmRubric {

    /**
     * Marker for code-based text assertions. Sealed; the {@code permits}
     * clause is a placeholder — concrete records (Contains, Equals, Regex)
     * are introduced by Tasks 04 and 05 by extending it.
     *
     * <p>{@link Placeholder} exists only to satisfy the JLS requirement that
     * a sealed type have at least one permitted subtype until real subtypes
     * are added. It carries no semantics and should not be matched against.</p>
     */
    sealed interface TextMatch extends EvaluationCriteria permits TextMatch.Placeholder {
        /** Placeholder; removed by Task 04/05 once real {@link TextMatch} kinds exist. */
        record Placeholder() implements TextMatch {}
    }

    /**
     * Grades model output against a free-text rubric using a judge model.
     * Concrete behaviour is added in Task 07.
     *
     * @param rubric      free-text grading rubric; must be non-null and non-blank
     * @param judgeModel  model that performs the grading; must be non-null
     */
    record LlmRubric(String rubric, ModelId judgeModel) implements EvaluationCriteria {
        public LlmRubric {
            if (rubric == null || rubric.isBlank())
                throw new IllegalArgumentException("rubric required");
            if (judgeModel == null)
                throw new IllegalArgumentException("judgeModel required");
        }
    }
}
