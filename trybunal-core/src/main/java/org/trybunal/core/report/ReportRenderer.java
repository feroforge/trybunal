package org.trybunal.core.report;

import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.eval.EvaluationReport.CaseResult;

/**
 * Stateless rendering of {@link EvaluationReport} for humans.
 *
 * <p>Two formats are supported: {@link #text(EvaluationReport)} for terminal
 * output and {@link #markdown(EvaluationReport)} for PR descriptions or
 * GitHub Actions step summaries.</p>
 */
public final class ReportRenderer {
    private ReportRenderer() {}

    public static String text(EvaluationReport report) {
        long totalMs = report.totalDuration().toMillis();
        int total = report.results().size();
        long passed = report.passCount();
        long failed = report.failCount();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Trybunal evaluation — %d cases — %d passed / %d failed — %d ms%n",
                total, passed, failed, totalMs));
        sb.append("─".repeat(61)).append('\n');

        for (CaseResult r : report.results()) {
            String tag = r.verdict().passed() ? "PASS" : "FAIL";
            long caseMs = r.invocation().metadata().latency().toMillis();
            sb.append(String.format("[%s] %s (%s, score=%.2f, %d ms)%n",
                    tag,
                    r.aCase().name(),
                    r.verdict().evaluatorId(),
                    r.verdict().score(),
                    caseMs));

            String rationale = r.verdict().rationale();
            if (!rationale.isBlank()) {
                sb.append(String.format("       rationale: \"%s\"%n", rationale));
            }

            if (!r.verdict().passed()) {
                String output = r.invocation().reply().content();
                if (output.length() > 200) {
                    output = output.substring(0, 200);
                }
                sb.append(String.format("       output: \"%s\"%n", output));
            }
        }

        return sb.toString();
    }

    public static String markdown(EvaluationReport report) {
        long totalMs = report.totalDuration().toMillis();
        long passed = report.passCount();
        int total = report.results().size();

        StringBuilder sb = new StringBuilder();
        sb.append("## Trybunal evaluation\n\n");
        sb.append(String.format("**%d / %d passed — %d ms**%n%n", passed, total, totalMs));
        sb.append("| Case | Evaluator | Result | Score | Latency | Rationale |\n");
        sb.append("|---|---|---|---|---|---|\n");

        for (CaseResult r : report.results()) {
            String icon = r.verdict().passed() ? "✅" : "❌";
            long caseMs = r.invocation().metadata().latency().toMillis();
            String rationale = r.verdict().rationale();
            if (rationale.length() > 120) {
                rationale = rationale.substring(0, 120);
            }
            rationale = rationale.isBlank() ? "—" : rationale.replace("|", "\\|");

            sb.append(String.format("| %s | %s | %s | %.2f | %d ms | %s |%n",
                    r.aCase().name(),
                    r.verdict().evaluatorId(),
                    icon,
                    r.verdict().score(),
                    caseMs,
                    rationale));
        }

        return sb.toString();
    }
}
