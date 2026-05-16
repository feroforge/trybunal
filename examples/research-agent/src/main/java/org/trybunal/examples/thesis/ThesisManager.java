package org.trybunal.examples.thesis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.spi.Tool;
import org.trybunal.core.Orchestrator;
import org.trybunal.core.Subagent;
import org.trybunal.core.SubagentSpec;
import org.trybunal.core.Subagents;
import org.trybunal.tool.citations.CitationReport;
import org.trybunal.tool.citations.CitationStore;
import org.trybunal.tool.mocks.MockTools;

/**
 * Subagent-as-tool variant of {@link ThesisAgent}.
 *
 * <p>Builds a manager orchestrator whose only two tools are subagents:
 * <ul>
 *   <li>{@code gather} — wraps a worker with {@code web_fetch},
 *       {@code safe_download}, and {@code cite}. Returns a brief.</li>
 *   <li>{@code summarise} — wraps a tool-less worker that writes the
 *       final markdown section given the manager's notes.</li>
 * </ul>
 * The manager then orchestrates them across the seven thesis sections.
 * Output mirrors {@link ThesisAgent}: one markdown file per section under
 * {@code build/thesis/<TICKER>/}.</p>
 *
 * <p>Run with mocks:
 * {@code ./gradlew :examples:research-agent:thesisManager
 *  --args="AAPL" -Dtrybunal.model=gemma4:26b -Dtrybunal.useMocks=true}.</p>
 */
public final class ThesisManager {

    /** Worker tools surfaced to the {@code gather} subagent. */
    private static final Set<String> GATHER_TOOLS = Set.of("web_fetch", "safe_download", "cite");

    /** Headroom for ReAct loops inside each subagent. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private ThesisManager() {}

    public static void main(String[] args) throws Exception {
        String ticker = (args.length > 0) ? args[0] : "AAPL";
        String modelName = System.getProperty("trybunal.model", "gemma4:26b");
        int managerIter = Integer.parseInt(System.getProperty("trybunal.maxIter", "12"));
        int workerIter = Integer.parseInt(System.getProperty("trybunal.workerMaxIter", "8"));
        ModelId model = new ModelId("ollama", modelName);

        Path outDir = Path.of("build", "thesis", ticker);
        Files.createDirectories(outDir);

        ModelProvider provider = discoverProvider("ollama");
        List<Tool> workerTools = loadWorkerTools();

        String today = LocalDate.now().toString();
        GenerationParams params = ThesisModelTuning.paramsFor(modelName,
                new GenerationParams(0.2, DEFAULT_MAX_TOKENS, null, 42L, Map.of(), List.of()));

        SubagentSpec gatherSpec = new SubagentSpec(
                "gather",
                "Gather cited facts for a thesis section. Argument: the section brief.",
                model,
                workerTools,
                ("""
                 You are a fact-gathering analyst. Today is %TODAY%. Ticker: %TICKER%.
                 Section to gather for: %TASK%
                 Use web_fetch / safe_download for sources and cite(...) for every
                 numeric claim. Return a short bulleted brief. Do not write the
                 final markdown — the summariser will do that.
                 """).replace("%TODAY%", today).replace("%TICKER%", ticker),
                workerIter,
                params);

        SubagentSpec summariseSpec = new SubagentSpec(
                "summarise",
                "Write the final markdown for a section given the manager's notes.",
                model,
                List.of(),
                ("""
                 You are a thesis writer. Today is %TODAY%. Ticker: %TICKER%.
                 The manager hands you a bulleted brief: %TASK%
                 Produce ONLY the markdown for the section. Cite numeric claims
                 inline as (source: ...). No preamble.
                 """).replace("%TODAY%", today).replace("%TICKER%", ticker),
                workerIter,
                params);

        try (Subagent gather = Subagents.asTool(gatherSpec, provider);
             Subagent summarise = Subagents.asTool(summariseSpec, provider);
             Orchestrator manager = Orchestrator.of(
                     List.of(provider), List.of(), List.of(gather, summarise), managerIter)) {

            System.out.println("Subagents registered: " + manager.registeredTools());

            String managerSystem = ("""
                    You are the lead analyst on a thesis for ticker %TICKER%. Today is %TODAY%.
                    For each section the user names, call gather(task=...) to collect
                    facts and then call summarise(task=...) with those facts to produce
                    the final markdown. Reply with the markdown returned by summarise.
                    """).replace("%TICKER%", ticker).replace("%TODAY%", today);
            PromptSession session = new PromptSession(
                    null, "thesis-manager", managerSystem,
                    Map.of(), List.of(), params, null);

            for (ThesisSections.Section section : ThesisSections.ALL) {
                Path file = outDir.resolve(section.slug() + ".md");
                System.out.println("→ " + section.slug() + " (via gather + summarise)");
                String userMsg = "Section: " + section.slug()
                        + " — " + section.question()
                        + "\nBrief template:\n" + ThesisSections.renderUserMessage(section, ticker);
                try {
                    InvocationResult result = manager.chat(session, model, userMsg);
                    String body = result.reply().content();
                    Files.writeString(file, body == null ? "" : body);
                } catch (RuntimeException e) {
                    // Per-section tolerance: don't abort all 7 just because one
                    // round-trip to Ollama tripped a transport error.
                    System.out.println("  ! " + section.slug() + " failed: " + e.getMessage());
                    Files.writeString(file, "<!-- failed: " + e.getMessage() + " -->\n");
                }
            }

            Path bib = outDir.resolve("citations.md");
            Files.writeString(bib,
                    CitationReport.renderMarkdown(CitationStore.shared().snapshot()));
            System.out.println("Done. " + outDir.toAbsolutePath());
        }
    }

    /** Loads the requested provider from ServiceLoader; throws if missing. */
    private static ModelProvider discoverProvider(String id) {
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
            if (id.equals(p.id())) return p;
        }
        throw new IllegalStateException("no provider registered with id=" + id);
    }

    /**
     * Picks the worker tools whose names appear in {@link #GATHER_TOOLS}, from
     * mocks when {@code -Dtrybunal.useMocks=true}, otherwise from
     * {@link ServiceLoader}. Returns a fresh list each call.
     */
    private static List<Tool> loadWorkerTools() {
        List<Tool> picked = new ArrayList<>();
        Iterable<Tool> candidates = MockTools.enabled()
                ? MockTools.all()
                : ServiceLoader.load(Tool.class);
        for (Tool t : candidates) {
            if (GATHER_TOOLS.contains(t.spec().name())) picked.add(t);
        }
        return picked;
    }
}
