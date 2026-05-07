package org.trybunal.api.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.trybunal.api.model.InvocationResult;

/**
 * Aggregate result of running a list of {@link EvaluationCase}s.
 *
 * @param startedAt      wall-clock instant the evaluation run began; non-null
 * @param totalDuration  total elapsed time of the run; non-null
 * @param results        per-case results; defensively copied; never null
 *                       (null is normalized to an empty list)
 */
public record EvaluationReport(
        Instant startedAt,
        Duration totalDuration,
        List<CaseResult> results
) {
    public EvaluationReport {
        if (startedAt == null) throw new IllegalArgumentException("startedAt required");
        if (totalDuration == null) throw new IllegalArgumentException("totalDuration required");
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** True iff every case produced a passing verdict. Empty report passes vacuously. */
    public boolean allPassed() { return results.stream().allMatch(r -> r.verdict().passed()); }

    /** Number of passing case results. */
    public long passCount()    { return results.stream().filter(r -> r.verdict().passed()).count(); }

    /** Number of non-passing case results. */
    public long failCount()    { return results.size() - passCount(); }

    /**
     * Result for a single case in an {@link EvaluationReport}.
     *
     * @param aCase       the case that was evaluated; non-null
     * @param invocation  the model invocation that produced the output graded
     *                    by the verdict; non-null
     * @param verdict     the evaluator's verdict; non-null
     */
    public record CaseResult(
            EvaluationCase aCase,
            InvocationResult invocation,
            EvaluationVerdict verdict
    ) {
        public CaseResult {
            if (aCase == null) throw new IllegalArgumentException("aCase required");
            if (invocation == null) throw new IllegalArgumentException("invocation required");
            if (verdict == null) throw new IllegalArgumentException("verdict required");
        }
    }
}
