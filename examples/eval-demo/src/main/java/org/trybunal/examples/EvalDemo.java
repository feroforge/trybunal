package org.trybunal.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.core.Orchestrator;
import org.trybunal.core.report.ReportRenderer;

public final class EvalDemo {
    public static void main(String[] args) throws Exception {
        String modelName = System.getProperty("trybunal.model", "llama3.1:8b");
        ModelId target = new ModelId("ollama", modelName);
        ModelId judge  = new ModelId("ollama", System.getProperty("trybunal.judge", modelName));

        PromptSession session = PromptSession.of(
                "eval-demo",
                "You are a concise, friendly assistant. Answer in one short sentence."
        );

        List<EvaluationCase> cases = List.of(
                new EvaluationCase(
                        "greets-by-name",
                        "Greet a user named Felix.",
                        new EvaluationCriteria.TextMatch.Contains("Felix", true)),
                new EvaluationCase(
                        "uses-digits",
                        "What is 2 + 2? Answer with digits only.",
                        new EvaluationCriteria.TextMatch.Regex("\\b4\\b",
                                EvaluationCriteria.TextMatch.Regex.MatchMode.FIND)),
                new EvaluationCase(
                        "polite-tone",
                        "Tell me you cannot help right now.",
                        new EvaluationCriteria.LlmRubric(
                                "The reply should politely decline without being rude.",
                                judge))
        );

        try (Orchestrator orch = Orchestrator.autoDiscover()) {
            EvaluationReport report = orch.evaluateAll(session, target, cases);
            System.out.println(ReportRenderer.text(report));

            Path out = Path.of("build", "eval-report.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, ReportRenderer.markdown(report));
            System.out.println("Markdown report written to: " + out.toAbsolutePath());

            System.exit(report.allPassed() ? 0 : 1);
        }
    }

    private EvalDemo() {}
}
