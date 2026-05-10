package org.trybunal.examples;

import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;

/**
 * One rubric-case output captured during phase-1 self-judging, retained so
 * a phase-2 heterogeneous judge can re-grade it without re-invoking the
 * target model.
 *
 * @param caseName        case identifier (e.g. {@code "A3-tool-negative-restraint"})
 * @param criteria        the original judge-style criteria
 *                        ({@link EvaluationCriteria.LlmRubric} or
 *                        {@link EvaluationCriteria.LlmRubricChecklist}).
 *                        Carries the rubric text / check list; the
 *                        {@code judgeModel} field is replaced when re-judged
 *                        by the cross-judge.
 * @param candidate       the target model's reply text — replayed verbatim
 *                        into the cross-judge
 * @param selfVerdict     the verdict the target model gave to its own output
 */
record SavedOutput(
        String caseName,
        EvaluationCriteria criteria,
        String candidate,
        EvaluationVerdict selfVerdict) {}
