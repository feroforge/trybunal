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

    /** Code-based text assertions. Sealed over {@link Contains} and {@link Equals} (Regex added in Task 05). */
    sealed interface TextMatch extends EvaluationCriteria
            permits TextMatch.Contains, TextMatch.Equals /*, TextMatch.Regex (Task 05) */ {

        record Contains(String needle, boolean caseSensitive) implements TextMatch {
            public Contains {
                if (needle == null || needle.isEmpty())
                    throw new IllegalArgumentException("needle required");
            }
        }

        record Equals(String expected, boolean trim) implements TextMatch {
            public Equals {
                if (expected == null) throw new IllegalArgumentException("expected required");
            }
        }
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
