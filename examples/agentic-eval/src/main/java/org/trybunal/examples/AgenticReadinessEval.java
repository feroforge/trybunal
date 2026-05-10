package org.trybunal.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.Evaluator;
import org.trybunal.core.Orchestrator;
import org.trybunal.core.report.ReportRenderer;

/**
 * Agentic-readiness evaluation runner.
 *
 * <p>Runs the {@link ReadinessSuite} against a list of Ollama models, one
 * model at a time (Ollama can only hold one model in VRAM). Phase 1 has
 * each model self-judge its own rubric outputs. Phase 2 then loads the
 * last-listed model and re-judges every other model's rubric outputs;
 * finally a second judge model re-judges the last model's outputs. See
 * {@link CrossJudge} for the replay machinery and {@link ReadinessReports}
 * for the markdown output.</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dtrybunal.models=a,b,c} — override the default model list.</li>
 *   <li>{@code -Dtrybunal.crossjudge2=name} — model used to judge the last
 *       model's outputs in phase 2b. Defaults to the first model in the list.</li>
 * </ul>
 *
 * <p>Outputs land in {@code build/agentic-eval/}: one markdown per model,
 * plus {@code summary.md} and {@code cross-judge.md}.</p>
 */
public final class AgenticReadinessEval {

    /** Models to evaluate, in order. Override with -Dtrybunal.models=a,b,c. */
    private static final List<String> DEFAULT_MODELS = List.of(
            "llama3.1:8b",
            "gemma4:26b",
            "gpt-oss:20b",
            "phi4-reasoning:latest",
            "mistral-small:24b",
            "phi4:14b"
    );

    public static void main(String[] args) throws Exception {
        List<String> models = parseModels(System.getProperty("trybunal.models"));
        Path outDir = Path.of("build", "agentic-eval");
        Files.createDirectories(outDir);

        List<RunSummary> summaries = new ArrayList<>(models.size());
        Map<String, List<SavedOutput>> savedByModel = new LinkedHashMap<>();

        try (Orchestrator orch = Orchestrator.autoDiscover()) {
            if (!orch.registeredProviders().contains("ollama")) {
                System.err.println("No ollama provider on classpath.");
                System.exit(2);
            }

            runPhase1(orch, models, outDir, summaries, savedByModel);

            Map<String, List<HetJudgeResult>> crossJudge = runPhase2(
                    models, outDir, savedByModel);

            Path summary = outDir.resolve("summary.md");
            Files.writeString(summary, ReadinessReports.renderSummary(
                    summaries, savedByModel, crossJudge));
            System.out.println();
            System.out.println("Summary written to: " + summary.toAbsolutePath());
        }
    }

    /** Runs the suite against every model with self-judging; saves rubric outputs. */
    private static void runPhase1(
            Orchestrator orch,
            List<String> models,
            Path outDir,
            List<RunSummary> summaries,
            Map<String, List<SavedOutput>> savedByModel) throws Exception {

        for (String modelName : models) {
            banner("Phase 1 — evaluating: " + modelName);

            ModelId target = new ModelId("ollama", modelName);
            ModelId judge  = target;  // self-judge

            // Canonical suite, then per-model tuning layered on top using
            // only PromptSession / GenerationParams / EvaluationCase
            // reconstruction — no new framework primitives.
            PromptSession session = ModelTuning.tuneSession(
                    modelName, ReadinessSuite.buildSession());
            List<EvaluationCase> cases = ModelTuning.tuneCases(
                    modelName, ReadinessSuite.buildSuite(judge));

            Instant t0 = Instant.now();
            EvaluationReport report;
            try {
                report = orch.evaluateAll(session, target, cases);
            } catch (RuntimeException e) {
                System.err.printf("[%s] evaluation failed: %s%n", modelName, e.getMessage());
                summaries.add(new RunSummary(modelName, 0, cases.size(),
                        Duration.between(t0, Instant.now()), "ERRORED: " + e.getMessage()));
                savedByModel.put(modelName, List.of());
                continue;
            }

            System.out.println(ReportRenderer.text(report));

            Path md = outDir.resolve(ReadinessReports.safeFilename(modelName) + ".md");
            Files.writeString(md, ReadinessReports.renderModelMarkdown(modelName, report));
            System.out.println("→ " + md.toAbsolutePath());

            summaries.add(new RunSummary(
                    modelName,
                    (int) report.passCount(),
                    report.results().size(),
                    report.totalDuration(),
                    null));
            savedByModel.put(modelName, CrossJudge.captureRubricOutputs(report));
        }
    }

    /**
     * Phase 2a uses the last-listed model (already warm) to judge everyone
     * else. Phase 2b loads a second judge to grade the last model's outputs.
     * Returns the per-model heterogeneous-judge results, or empty when there
     * are too few models for cross-judging to be meaningful.
     */
    private static Map<String, List<HetJudgeResult>> runPhase2(
            List<String> models,
            Path outDir,
            Map<String, List<SavedOutput>> savedByModel) throws Exception {

        Map<String, List<HetJudgeResult>> crossJudge = new LinkedHashMap<>();
        if (models.size() < 2 || savedByModel.isEmpty()) {
            System.out.println("Skipping phase 2 — need at least 2 models for cross-judging.");
            return crossJudge;
        }

        String lastModel = models.get(models.size() - 1);
        String secondJudgeName = System.getProperty(
                "trybunal.crossjudge2", models.get(0));

        Evaluator rubricEval = CrossJudge.findRubricEvaluator();

        banner("Phase 2a — cross-judge (judge = " + lastModel + ")");
        ModelId firstJudge = new ModelId("ollama", lastModel);
        for (String modelName : models) {
            if (modelName.equals(lastModel)) continue;
            List<SavedOutput> saved = savedByModel.getOrDefault(modelName, List.of());
            crossJudge.put(modelName, CrossJudge.rejudge(rubricEval, saved, firstJudge, modelName));
        }

        banner("Phase 2b — cross-judge (judge = " + secondJudgeName + ")");
        ModelId secondJudge = new ModelId("ollama", secondJudgeName);
        List<SavedOutput> savedLast = savedByModel.getOrDefault(lastModel, List.of());
        crossJudge.put(lastModel, CrossJudge.rejudge(rubricEval, savedLast, secondJudge, lastModel));

        Path crossPath = outDir.resolve("cross-judge.md");
        Files.writeString(crossPath, ReadinessReports.renderCrossJudge(
                models, lastModel, secondJudgeName, savedByModel, crossJudge));
        System.out.println("→ " + crossPath.toAbsolutePath());
        return crossJudge;
    }

    private static void banner(String label) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("  " + label);
        System.out.println("══════════════════════════════════════════════════════════════");
    }

    private static List<String> parseModels(String prop) {
        if (prop == null || prop.isBlank()) return DEFAULT_MODELS;
        var out = new ArrayList<String>();
        for (String p : prop.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? DEFAULT_MODELS : List.copyOf(out);
    }

    private AgenticReadinessEval() {}
}
