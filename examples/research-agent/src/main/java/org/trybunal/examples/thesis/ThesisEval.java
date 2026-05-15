package org.trybunal.examples.thesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.core.Orchestrator;
import org.trybunal.core.report.ReportRenderer;

/**
 * Multi-model evaluation of the thesis-gathering sub-agent.
 *
 * <p>For each model in {@code -Dtrybunal.models}, this runner:</p>
 * <ol>
 *   <li>Builds a low-temperature, agent-loop {@link PromptSession} with
 *       per-model token budget from {@link ThesisModelTuning}.</li>
 *   <li>Runs the seven-case suite from {@link ThesisRubrics} sequentially
 *       (one case at a time — Ollama only holds one model in VRAM, and
 *       parallelising six full ReAct loops would also blast the
 *       configured search provider's rate limit).</li>
 *   <li>Saves the per-model markdown report and the candidate output for
 *       every section.</li>
 * </ol>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dtrybunal.models=a,b,c} — models to compare (default:
 *       {@code llama3.1:8b}).</li>
 *   <li>{@code -Dtrybunal.judge=name} — judge model used for the rubric
 *       checklists. Defaults to the first model in the target list, which
 *       gives a self-judged baseline; pick a different judge to produce a
 *       heterogeneous-judge run.</li>
 *   <li>{@code -Dtrybunal.ticker=AAPL} — ticker under evaluation.</li>
 * </ul>
 *
 * <p>Outputs land in {@code build/thesis-eval/}: one markdown per model
 * plus {@code summary.md}.</p>
 */
public final class ThesisEval {

    private static final List<String> DEFAULT_MODELS = List.of("llama3.1:8b");

    /**
     * Ollama models that have been observed to return
     * {@code "<model> does not support tools"} on first invocation. They are
     * still useful as <i>judges</i> (judging is a single non-tool inference)
     * so we don't reject them outright — we just refuse to make them
     * <i>targets</i>. Update by appending; matched as a string-equals on
     * {@code modelName}.
     */
    private static final java.util.Set<String> NON_TOOL_TARGETS = java.util.Set.of(
            "phi4:14b",
            "phi4-reasoning:latest"
    );

    public static void main(String[] args) throws Exception {
        List<String> models = parseModels(System.getProperty("trybunal.models"));
        String ticker = System.getProperty("trybunal.ticker", "AAPL");
        String judgeName = System.getProperty("trybunal.judge", models.get(0));
        ModelId judge = new ModelId("ollama", judgeName);

        Path outDir = Path.of("build", "thesis-eval");
        Files.createDirectories(outDir);

        List<ModelRun> runs = new ArrayList<>();

        try (Orchestrator orch = Orchestrator.autoDiscover()) {
            requireProvider(orch);
            requireTools(orch);

            for (String modelName : models) {
                if (NON_TOOL_TARGETS.contains(modelName)) {
                    System.out.println("Skipping " + modelName
                            + " as a target: model does not support tool-calling on Ollama. "
                            + "(It can still be used as -Dtrybunal.judge.)");
                    runs.add(new ModelRun(modelName, null, Duration.ZERO,
                            "skipped: model does not support tools"));
                    continue;
                }
                banner("Evaluating: " + modelName + "  (judge: " + judgeName + ")");
                ModelId target = new ModelId("ollama", modelName);
                PromptSession session = sessionFor(modelName, ticker);
                List<EvaluationCase> cases = ThesisRubrics.buildSuite(ticker, judge);

                Instant t0 = Instant.now();
                EvaluationReport report;
                try {
                    // Sequential per-case: see class javadoc.
                    report = evaluateSequential(orch, session, target, cases);
                } catch (RuntimeException e) {
                    System.err.printf("[%s] aborted: %s%n", modelName, e.getMessage());
                    runs.add(new ModelRun(modelName, null,
                            Duration.between(t0, Instant.now()),
                            "ERRORED: " + e.getMessage()));
                    continue;
                }

                System.out.println(ReportRenderer.text(report));
                Path md = outDir.resolve(safeFilename(modelName) + ".md");
                Files.writeString(md, ThesisReports.renderModelMarkdown(modelName, ticker, report));
                System.out.println("→ " + md.toAbsolutePath());
                runs.add(new ModelRun(modelName, report,
                        Duration.between(t0, Instant.now()), null));
            }

            Path summary = outDir.resolve("summary.md");
            Files.writeString(summary,
                    ThesisReports.renderSummary(ticker, judgeName, runs));
            System.out.println("Summary: " + summary.toAbsolutePath());
        }
    }

    /** One row of the summary table. */
    record ModelRun(String model, EvaluationReport report, Duration duration, String error) {}

    /**
     * Drive {@code orch.evaluate(...)} per case, accumulating the same
     * {@link EvaluationReport} shape that {@code evaluateAll} would
     * produce. Sequential by design — see class javadoc.
     */
    private static EvaluationReport evaluateSequential(
            Orchestrator orch, PromptSession session, ModelId target,
            List<EvaluationCase> cases) {
        Instant startedAt = Instant.now();
        long t0 = System.nanoTime();
        var results = new ArrayList<EvaluationReport.CaseResult>(cases.size());
        for (EvaluationCase c : cases) {
            System.out.println("  · " + c.name());
            // Single-case path. Capture the invocation by re-running it
            // through the orchestrator's chat to keep the same code path
            // evaluateAll uses; the orchestrator's evaluate() returns only
            // a verdict and discards the invocation, which we want for
            // the per-section markdown.
            // Instead we use the public evaluate which discards invocation.
            // Trade: rerun. For our use, we re-invoke via agent() and
            // grade with a manually-constructed verdict.
            org.trybunal.api.model.InvocationResult inv =
                    orch.agent(session, target, c.userMessage());
            org.trybunal.api.eval.EvaluationVerdict verdict =
                    new org.trybunal.evaluator.llmjudge.LlmJudgeEvaluator(
                            findOllamaProvider(orch))
                            .evaluate(inv, c.criteria());
            results.add(new EvaluationReport.CaseResult(c, inv, verdict));
        }
        return new EvaluationReport(startedAt,
                Duration.ofNanos(System.nanoTime() - t0), results);
    }

    /**
     * Pulls the Ollama {@link org.trybunal.api.spi.ModelProvider} back out of
     * the orchestrator. We need a direct reference because the auto-discovered
     * {@link org.trybunal.evaluator.llmjudge.LlmJudgeEvaluator} resolves its
     * provider by service-loading too — and inside an embedded run we want a
     * single, guaranteed-present judge instance instead.
     */
    private static org.trybunal.api.spi.ModelProvider findOllamaProvider(Orchestrator orch) {
        for (org.trybunal.api.spi.ModelProvider p :
                java.util.ServiceLoader.load(org.trybunal.api.spi.ModelProvider.class)) {
            if ("ollama".equals(p.id())) return p;
        }
        throw new IllegalStateException("ollama provider not on classpath");
    }

    private static PromptSession sessionFor(String modelName, String ticker) {
        String today = LocalDate.now().toString();
        String sysPrompt = ThesisSections.renderSystemPrompt(ticker, today);
        GenerationParams params = ThesisModelTuning.paramsFor(modelName,
                new GenerationParams(0.2, 4096, null, 42L, Map.of(), List.of()));
        return new PromptSession(null, "thesis-eval-" + modelName, sysPrompt,
                Map.of(), List.of(), params, null);
    }

    private static void requireProvider(Orchestrator orch) {
        if (!orch.registeredProviders().contains("ollama")) {
            System.err.println("No ollama provider on classpath.");
            System.exit(2);
        }
    }

    private static void requireTools(Orchestrator orch) {
        var tools = orch.registeredTools();
        var required = List.of("web_search", "web_fetch", "cite");
        for (String t : required) {
            if (!tools.contains(t)) {
                System.err.println("Missing required tool: " + t
                        + ". Have: " + tools);
                System.exit(2);
            }
        }
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

    static String safeFilename(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void banner(String label) {
        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("  " + label);
        System.out.println("══════════════════════════════════════════════════════════════");
    }

    /** Map alias used by report renderer to look up section titles by slug. */
    static Map<String, String> sectionTitlesBySlug() {
        var out = new LinkedHashMap<String, String>();
        for (var s : ThesisSections.ALL) out.put(s.slug(), s.question());
        return out;
    }

    private ThesisEval() {}
}
