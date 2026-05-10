package org.trybunal.examples;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.core.report.ReportRenderer;

/**
 * Markdown rendering for the agentic-readiness suite.
 *
 * <p>Three artifacts:</p>
 * <ul>
 *   <li>{@link #renderModelMarkdown} — one file per model with per-dimension
 *       tally, the standard {@link ReportRenderer} table, and raw outputs.</li>
 *   <li>{@link #renderSummary} — top-level table with self-vs-het rubric
 *       columns so bias is visible at a glance.</li>
 *   <li>{@link #renderCrossJudge} — disagreement matrix per model, including
 *       which judge graded which output.</li>
 * </ul>
 *
 * <p>Stateless. Static methods only.</p>
 */
final class ReadinessReports {

    private ReadinessReports() {}

    /** Per-model markdown: per-dimension tally + standard table + raw outputs. */
    static String renderModelMarkdown(String modelName, EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agentic readiness — `").append(modelName).append("`\n\n");
        sb.append("**Self-judged run.** Same model used as both target and judge.\n\n");

        // Group results by dimension prefix (A/B/C/D).
        Map<String, int[]> byCat = new LinkedHashMap<>();
        byCat.put("A. Tool use",        new int[]{0, 0});
        byCat.put("B. Decomposition",   new int[]{0, 0});
        byCat.put("C. Reasoning",       new int[]{0, 0});
        byCat.put("D. Facts→Args→Spec", new int[]{0, 0});
        for (var r : report.results()) {
            String cat = switch (r.aCase().name().substring(0, 1)) {
                case "A" -> "A. Tool use";
                case "B" -> "B. Decomposition";
                case "C" -> "C. Reasoning";
                case "D" -> "D. Facts→Args→Spec";
                default  -> "?";
            };
            int[] pf = byCat.get(cat);
            if (pf != null) {
                pf[0] += r.verdict().passed() ? 1 : 0;
                pf[1] += 1;
            }
        }
        sb.append("## Per-dimension\n\n| Dimension | Passed |\n|---|---|\n");
        for (var e : byCat.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ")
              .append(e.getValue()[0]).append(" / ").append(e.getValue()[1])
              .append(" |\n");
        }
        sb.append('\n');

        sb.append(ReportRenderer.markdown(report));

        // Append raw outputs for human inspection.
        sb.append("\n## Raw outputs\n\n");
        for (var r : report.results()) {
            sb.append("### ").append(r.aCase().name())
              .append(" — ").append(r.verdict().passed() ? "PASS" : "FAIL").append("\n\n");
            sb.append("```\n").append(r.invocation().reply().content()).append("\n```\n\n");
        }
        return sb.toString();
    }

    /** Top-level summary: total pass-rate plus self-vs-het rubric columns. */
    static String renderSummary(
            List<RunSummary> rows,
            Map<String, List<SavedOutput>> savedByModel,
            Map<String, List<HetJudgeResult>> crossJudge) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agentic readiness — summary\n\n");
        sb.append("Per-model totals combine deterministic regex/contains checks\n");
        sb.append("with self-judged rubric checks. The two rubric columns separate\n");
        sb.append("self-judgment from a heterogeneous-judge pass; large gaps between\n");
        sb.append("them flag bias in self-grading.\n\n");
        sb.append("| Model | Total pass rate | Self rubric | Het. rubric | Δ | Duration | Notes |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (var s : rows) {
            String rate = s.total() == 0 ? "—"
                    : String.format("%d/%d (%.0f%%)", s.passed(), s.total(),
                            100.0 * s.passed() / s.total());

            List<SavedOutput> saved = savedByModel.getOrDefault(s.model(), List.of());
            int rubTotal = saved.size();
            long selfPass = saved.stream().filter(o -> o.selfVerdict().passed()).count();
            String selfStr = rubTotal == 0 ? "—"
                    : String.format("%d/%d", selfPass, rubTotal);

            List<HetJudgeResult> het = crossJudge.getOrDefault(s.model(), List.of());
            String hetStr;
            String delta;
            if (het.isEmpty()) {
                hetStr = "—";
                delta  = "—";
            } else {
                long hetPass = het.stream().filter(h -> h.hetVerdict().passed()).count();
                hetStr = String.format("%d/%d", hetPass, het.size());
                delta  = String.format("%+d", (int)(hetPass - selfPass));
            }

            sb.append("| `").append(s.model()).append("` | ")
              .append(rate).append(" | ")
              .append(selfStr).append(" | ")
              .append(hetStr).append(" | ")
              .append(delta).append(" | ")
              .append(s.duration().toSeconds()).append("s | ")
              .append(s.note() == null ? "—" : s.note().replace("|", "\\|"))
              .append(" |\n");
        }
        sb.append("\n");
        sb.append("**Het. rubric** is each model's rubric outputs re-graded by a\n");
        sb.append("different model — see [cross-judge.md](cross-judge.md) for the\n");
        sb.append("disagreement matrix.\n");
        return sb.toString();
    }

    /** Per-model self-vs-heterogeneous verdict matrix with disagreement flags. */
    static String renderCrossJudge(
            List<String> models,
            String lastModel,
            String secondJudgeName,
            Map<String, List<SavedOutput>> savedByModel,
            Map<String, List<HetJudgeResult>> crossJudge) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Cross-judge report\n\n");
        sb.append("Each rubric case (A3, B3, C3, D1, D2) is shown with two\n");
        sb.append("verdicts: the model's own self-grade, and a verdict from a\n");
        sb.append("different model loaded after the fact and replayed against\n");
        sb.append("the saved candidate text. Disagreements are flagged.\n\n");

        sb.append("- **Primary cross-judge:** `").append(lastModel).append("`")
          .append(" — graded every model except itself.\n");
        sb.append("- **Secondary cross-judge:** `").append(secondJudgeName).append("`")
          .append(" — graded `").append(lastModel).append("`.\n\n");

        // Aggregate disagreement count.
        int totalCases = 0, disagreements = 0;
        for (var entry : crossJudge.entrySet()) {
            for (HetJudgeResult h : entry.getValue()) {
                totalCases++;
                if (h.saved().selfVerdict().passed() != h.hetVerdict().passed()) disagreements++;
            }
        }
        sb.append(String.format("**Overall self↔het agreement: %d / %d cases (%d disagreements)**%n%n",
                totalCases - disagreements, totalCases, disagreements));

        for (String modelName : models) {
            List<HetJudgeResult> het = crossJudge.get(modelName);
            if (het == null || het.isEmpty()) continue;
            String judgeUsed = modelName.equals(lastModel) ? secondJudgeName : lastModel;

            sb.append("## `").append(modelName).append("` ")
              .append("(het. judge: `").append(judgeUsed).append("`)\n\n");
            sb.append("| Case | Self | Het. | Agree | Self rationale | Het. rationale |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (HetJudgeResult h : het) {
                String selfIcon = h.saved().selfVerdict().passed() ? "✅" : "❌";
                String hetIcon  = h.hetVerdict().passed()          ? "✅" : "❌";
                String agree    = h.saved().selfVerdict().passed() == h.hetVerdict().passed() ? "✓" : "⚠︎";
                sb.append("| ").append(h.saved().caseName()).append(" | ")
                  .append(selfIcon).append(" | ").append(hetIcon).append(" | ")
                  .append(agree).append(" | ")
                  .append(truncCell(h.saved().selfVerdict().rationale())).append(" | ")
                  .append(truncCell(h.hetVerdict().rationale())).append(" |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Filename-safe form of an arbitrary model id. */
    static String safeFilename(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncCell(String s) {
        if (s == null || s.isBlank()) return "—";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').replace("|", "\\|");
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }
}
