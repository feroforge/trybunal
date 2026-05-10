package org.trybunal.api.eval;

import java.util.List;
import org.trybunal.api.model.ModelId;

/**
 * The criterion that an {@link EvaluationCase} is checked against.
 *
 * <p>Sealed over three families:</p>
 * <ul>
 *   <li>{@link TextMatch} — code-based assertions over the model's output text.
 *       Itself sealed; concrete kinds (Contains, Equals, Regex, ...) are added
 *       in later tasks.</li>
 *   <li>{@link LlmRubric} — grade the output with another model against a
 *       free-text rubric.</li>
 *   <li>{@link LlmRubricChecklist} — grade against a list of binary checks
 *       judged in a single inference; pass requires all checks to be true.
 *       Removes the "one bad sentence in the rationale loses the case"
 *       failure mode of free-text rubrics.</li>
 * </ul>
 */
public sealed interface EvaluationCriteria
        permits EvaluationCriteria.TextMatch,
                EvaluationCriteria.LlmRubric,
                EvaluationCriteria.LlmRubricChecklist {

    /** Code-based text assertions. Sealed over {@link Contains}, {@link Equals}, and {@link Regex}. */
    sealed interface TextMatch extends EvaluationCriteria
            permits TextMatch.Contains, TextMatch.Equals, TextMatch.Regex {

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

        record Regex(String pattern, MatchMode mode) implements TextMatch {
            public Regex {
                if (pattern == null || pattern.isBlank())
                    throw new IllegalArgumentException("pattern required");
                if (mode == null) mode = MatchMode.FIND;
            }
            public enum MatchMode { FIND, FULL_MATCH }
        }
    }

    /**
     * Grades model output against a free-text rubric using a judge model.
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

    /**
     * Grades model output against a list of binary checks. The judge model
     * receives all checks in one inference and returns one boolean per
     * check; the case passes iff every check is true.
     *
     * <p><b>Why this exists.</b> A free-text {@link LlmRubric} loses the
     * case to any single mis-step in the judge's one-paragraph rationale —
     * if the judge hallucinates a constraint, misreads a list count, or
     * conflates two requirements, the verdict is wrong. Forcing the judge
     * to commit to a verdict per check makes those errors localized
     * (one check fails, not the whole case) and makes the rationale
     * machine-inspectable per item.</p>
     *
     * <p>The {@code score} on the resulting verdict is
     * {@code passingChecks / totalChecks}, so a partial-pass case can
     * still surface signal even when {@code passed=false}.</p>
     *
     * @param checks      ordered list of binary check statements; non-null,
     *                    non-empty; each entry must be non-blank.
     *                    Defensively copied.
     * @param judgeModel  model that performs the grading; non-null
     */
    record LlmRubricChecklist(List<String> checks, ModelId judgeModel) implements EvaluationCriteria {
        public LlmRubricChecklist {
            if (checks == null || checks.isEmpty())
                throw new IllegalArgumentException("checks required");
            for (String c : checks) {
                if (c == null || c.isBlank())
                    throw new IllegalArgumentException("each check must be non-blank");
            }
            if (judgeModel == null)
                throw new IllegalArgumentException("judgeModel required");
            checks = List.copyOf(checks);
        }
    }
}
