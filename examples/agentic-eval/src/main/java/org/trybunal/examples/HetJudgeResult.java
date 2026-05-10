package org.trybunal.examples;

import org.trybunal.api.eval.EvaluationVerdict;

/**
 * Pairs a phase-1 {@link SavedOutput} with the verdict produced by a
 * heterogeneous (different-from-target) judge model that re-graded the
 * saved candidate text.
 *
 * @param saved       the original saved output (carries the self-verdict)
 * @param hetVerdict  the verdict from the heterogeneous judge
 */
record HetJudgeResult(SavedOutput saved, EvaluationVerdict hetVerdict) {}
