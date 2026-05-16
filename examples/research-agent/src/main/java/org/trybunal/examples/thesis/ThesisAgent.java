package org.trybunal.examples.thesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.core.Orchestrator;
import org.trybunal.tool.citations.CitationReport;
import org.trybunal.tool.citations.CitationStore;

/**
 * Runs the seven {@link ThesisSections} sub-agents for a single ticker and
 * writes the gathered material to {@code build/thesis/<TICKER>/}.
 *
 * <p>Run: {@code ./gradlew :examples:research-agent:run --args="thesis AAPL"}
 * (the {@code thesis} keyword routes here; bare ticker still goes to the
 * older single-call {@code ResearchAgent}).</p>
 *
 * <p>Each sub-agent gets its own {@link PromptSession} so the system prompt
 * can be specialised with ticker and date, and so tokens generated for one
 * section don't leak into the next.</p>
 */
public final class ThesisAgent {

    /** Per-section max tokens. ReAct loops + bullet-list output need headroom. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private ThesisAgent() {}

    public static void main(String[] args) throws Exception {
        String ticker = (args.length > 0) ? args[0] : "AAPL";
        String modelName = System.getProperty("trybunal.model", "llama3.1:8b");
        int maxIter = Integer.parseInt(System.getProperty("trybunal.maxIter", "16"));
        ModelId model = new ModelId("ollama", modelName);

        Path outDir = Path.of("build", "thesis", ticker);
        Files.createDirectories(outDir);

        try (Orchestrator orch = Orchestrator.autoDiscover(maxIter)) {
            for (ThesisSections.Section section : ThesisSections.ALL) {
                Path file = outDir.resolve(section.slug() + ".md");
                System.out.println("→ " + section.slug());
                String md = runSection(orch, model, ticker, section);
                Files.writeString(file, md);
            }
            // One global bibliography spanning every sub-agent's citations.
            Path bib = outDir.resolve("citations.md");
            Files.writeString(bib,
                    CitationReport.renderMarkdown(CitationStore.shared().snapshot()));
            System.out.println("Done. " + outDir.toAbsolutePath());
            System.out.println("Tools registered: " + orch.registeredTools());
        }
    }

    /**
     * Single sub-agent run. Builds a tuned session for {@code modelName},
     * dispatches the user prompt through {@link Orchestrator#agent}, and
     * returns the model's reply unchanged. Caller writes it to disk.
     */
    static String runSection(Orchestrator orch, ModelId model,
                             String ticker, ThesisSections.Section section) {
        String today = LocalDate.now().toString();
        String sysPrompt = ThesisSections.renderSystemPrompt(ticker, today);
        GenerationParams params = ThesisModelTuning.paramsFor(
                model.name(),
                new GenerationParams(0.2, DEFAULT_MAX_TOKENS, null, 42L,
                        Map.of(), List.of()));
        PromptSession session = new PromptSession(
                null, "thesis-" + section.slug(), sysPrompt,
                Map.of(), List.of(), params, null);

        String userMsg = ThesisSections.renderUserMessage(section, ticker);
        InvocationResult result = orch.agent(session, model, userMsg);
        return result.reply().content();
    }
}
