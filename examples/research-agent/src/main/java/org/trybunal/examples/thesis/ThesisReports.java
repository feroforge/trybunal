package org.trybunal.examples.thesis;

import java.util.List;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.core.report.ReportRenderer;

/**
 * Markdown rendering for {@link ThesisEval}.
 *
 * <p>Two artifacts:</p>
 * <ul>
 *   <li>{@link #renderModelMarkdown} — one file per model. Per-section
 *       pass/fail row, the standard {@link ReportRenderer} table, and the
 *       full candidate output for each section so a human can spot-check
 *       whether the rubric checklist was generous, harsh, or wrong.</li>
 *   <li>{@link #renderSummary} — top-level table comparing models on
 *       overall pass-rate, per-section pass count, and total wall time.</li>
 * </ul>
 *
 * <p>Stateless. Static methods only.</p>
 */
final class ThesisReports {

    private ThesisReports() {}

    static String renderModelMarkdown(String modelName, String ticker, EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Thesis-gathering eval — `").append(modelName).append("`\n\n");
        sb.append("**Ticker:** ").append(ticker).append("\n\n");

        sb.append("## Per-section\n\n");
        sb.append("| Section | Verdict | Score | Rationale (truncated) |\n");
        sb.append("|---|---|---|---|\n");
        for (var r : report.results()) {
            sb.append("| ").append(r.aCase().name()).append(" | ")
              .append(r.verdict().passed() ? "✅" : "❌").append(" | ")
              .append(String.format("%.2f", r.verdict().score())).append(" | ")
              .append(truncCell(r.verdict().rationale())).append(" |\n");
        }
        sb.append('\n');

        sb.append(ReportRenderer.markdown(report));

        sb.append("\n## Candidate outputs\n\n");
        for (var r : report.results()) {
            sb.append("### ").append(r.aCase().name())
              .append(" — ").append(r.verdict().passed() ? "PASS" : "FAIL")
              .append("\n\n```markdown\n")
              .append(r.invocation().reply().content())
              .append("\n```\n\n");
        }
        return sb.toString();
    }

    static String renderSummary(String ticker, String judgeName, List<ThesisEval.ModelRun> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Thesis-gathering eval — summary\n\n");
        sb.append("Each model ran the seven-section gathering suite for ticker ")
          .append("**").append(ticker).append("**. ")
          .append("Verdicts come from a `LlmRubricChecklist` graded by `")
          .append(judgeName).append("`.\n\n");
        sb.append("| Model | Pass rate | Per-section | Duration | Notes |\n");
        sb.append("|---|---|---|---|---|\n");
        for (var run : runs) {
            String rate, perSection;
            if (run.report() == null) {
                rate = "—"; perSection = "—";
            } else {
                int passed = (int) run.report().passCount();
                int total = run.report().results().size();
                rate = String.format("%d/%d (%.0f%%)",
                        passed, total, 100.0 * passed / Math.max(1, total));
                StringBuilder ps = new StringBuilder();
                for (var r : run.report().results()) {
                    ps.append(r.verdict().passed() ? "✅" : "❌");
                }
                perSection = ps.toString();
            }
            sb.append("| `").append(run.model()).append("` | ")
              .append(rate).append(" | ")
              .append(perSection).append(" | ")
              .append(run.duration().toSeconds()).append("s | ")
              .append(run.error() == null ? "—" : run.error().replace("|", "\\|"))
              .append(" |\n");
        }
        sb.append('\n');
        sb.append("Per-section emoji order matches `ThesisSections.ALL`: ");
        for (var s : ThesisSections.ALL) sb.append(s.slug()).append(" · ");
        sb.setLength(sb.length() - 3);
        sb.append("\n");
        return sb.toString();
    }

    private static String truncCell(String s) {
        if (s == null || s.isBlank()) return "—";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').replace("|", "\\|");
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
