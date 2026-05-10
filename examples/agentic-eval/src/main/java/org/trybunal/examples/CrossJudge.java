package org.trybunal.examples;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.Evaluator;

/**
 * Heterogeneous cross-judging utilities.
 *
 * <p>Phase 1 evaluates each model with itself as the judge. Phase 2 replays
 * the saved candidate text (no new generation against the target) through a
 * different judge model and produces a parallel verdict per case. The two
 * verdicts are kept side-by-side so the {@link ReadinessReports} layer can
 * surface disagreements.</p>
 */
final class CrossJudge {

    private CrossJudge() {}

    /** Captures the {target → candidate} pairs for every judge-style case in a report. */
    static List<SavedOutput> captureRubricOutputs(EvaluationReport report) {
        var out = new ArrayList<SavedOutput>();
        for (var r : report.results()) {
            EvaluationCriteria c = r.aCase().criteria();
            if (c instanceof EvaluationCriteria.LlmRubric
                    || c instanceof EvaluationCriteria.LlmRubricChecklist) {
                out.add(new SavedOutput(
                        r.aCase().name(),
                        c,
                        r.invocation().reply().content(),
                        r.verdict()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Re-judges {@code saved} by replaying each candidate through a freshly
     * constructed criterion pinned to {@code judge}. Handles both
     * {@link EvaluationCriteria.LlmRubric} and
     * {@link EvaluationCriteria.LlmRubricChecklist}. Failures are caught
     * per case and surfaced as {@code passed=false} verdicts.
     */
    static List<HetJudgeResult> rejudge(
            Evaluator rubricEval,
            List<SavedOutput> saved,
            ModelId judge,
            String targetModelName) {
        var out = new ArrayList<HetJudgeResult>(saved.size());
        for (SavedOutput s : saved) {
            EvaluationCriteria pinned = withJudge(s.criteria(), judge);
            var fakeMeta = new InvocationMetadata(
                    new ModelId("ollama", targetModelName),
                    Instant.now(), Duration.ZERO,
                    null, null, List.of(), "REPLAY");
            var fakeResult = new InvocationResult(
                    Message.Assistant.of(s.candidate()), fakeMeta);
            EvaluationVerdict v;
            try {
                v = rubricEval.evaluate(fakeResult, pinned);
            } catch (RuntimeException e) {
                v = new EvaluationVerdict(false, 0.0, "llm-judge",
                        "Cross-judge invocation failed: " + e.getMessage(), Map.of());
            }
            System.out.printf("  [%s :: %s] het=%s (self=%s)%n",
                    targetModelName, s.caseName(),
                    v.passed() ? "PASS" : "FAIL",
                    s.selfVerdict().passed() ? "PASS" : "FAIL");
            out.add(new HetJudgeResult(s, v));
        }
        return List.copyOf(out);
    }

    /** Returns {@code original} with its judge model swapped for {@code judge}. */
    private static EvaluationCriteria withJudge(EvaluationCriteria original, ModelId judge) {
        return switch (original) {
            case EvaluationCriteria.LlmRubric r ->
                    new EvaluationCriteria.LlmRubric(r.rubric(), judge);
            case EvaluationCriteria.LlmRubricChecklist l ->
                    new EvaluationCriteria.LlmRubricChecklist(l.checks(), judge);
            default -> throw new IllegalStateException(
                    "unexpected criteria type: " + original.getClass());
        };
    }

    /** Looks up a registered {@link Evaluator} that handles {@link EvaluationCriteria.LlmRubric}. */
    static Evaluator findRubricEvaluator() {
        var probe = new EvaluationCriteria.LlmRubric("probe",
                new ModelId("ollama", "probe"));
        for (Evaluator e : ServiceLoader.load(Evaluator.class)) {
            if (e.supports(probe)) return e;
        }
        throw new IllegalStateException(
                "no LlmRubric evaluator on classpath — is trybunal-evaluator-llm-judge a runtime dep?");
    }
}
