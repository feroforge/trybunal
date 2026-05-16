package org.trybunal.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;
import org.trybunal.core.Orchestrator;
import org.trybunal.examples.thesis.ThesisModelTuning;
import org.trybunal.tool.citations.CitationReport;
import org.trybunal.tool.citations.CitationStore;

/**
 * End-to-end demo wiring every Phase 3 tool together: a ReAct loop with
 * web_search, web_fetch, web_browser, safe_download, and cite. Default
 * scenario is a stock-research prompt for a given ticker.
 *
 * <p>Run: {@code ./gradlew :examples:research-agent:run --args="AAPL"}.
 * Output lands at {@code build/research-<TICKER>.md}.</p>
 */
public final class ResearchAgent {

    private ResearchAgent() {}

    public static void main(String[] args) throws Exception {
        String ticker = (args.length > 0) ? args[0] : "AAPL";
        String modelName = System.getProperty("trybunal.model", "llama3.1:8b");
        int maxIter = Integer.parseInt(System.getProperty("trybunal.maxIter", "16"));
        ModelId model = new ModelId("ollama", modelName);

        // Comma-separated tool names to drop. Useful when an external provider
        // (e.g. DuckDuckGo) is rate-limited and the model would otherwise
        // exhaust its iteration cap retrying the broken tool. Read here so the
        // system prompt can omit any reference to dropped tools.
        String skip = System.getProperty("trybunal.skipTools", "");
        var skipSet = new java.util.HashSet<String>();
        for (String s : skip.split(",")) {
            String n = s.trim();
            if (!n.isEmpty()) skipSet.add(n);
        }
        boolean hasSearch = !skipSet.contains("web_search");

        // IMPORTANT: keep this prompt SHORT. A 2 000-char system prompt
        // combined with 2+ tool-result exchanges causes the Ollama gemma3
        // chat template to return an empty placeholder
        // ({"model":"","done":false,...}) and crash the ReAct loop. Reduce
        // here and put structural instructions in the user message.
        String today = LocalDate.now().toString();
        String systemPrompt = ("""
                You are a research analyst. Today is %TODAY%. Use tools to gather
                facts about ticker %TICKER%, then write a short summary.
                Use the `todo` tool first to register a plan; mark items off as
                you finish them. For sha256 you may use sixty-four zero hex chars
                if no real hash is available.
                """)
                .replace("%TODAY%", today)
                .replace("%TICKER%", ticker);
        // %SEARCH_LINE% is no longer interpolated; keep the variable referenced
        // so an unused-warning does not fire and the option stays documented.
        if (!hasSearch) {
            // explicit no-op: web_search is not loaded into the orchestrator
        }

        GenerationParams params = ThesisModelTuning.paramsFor(modelName,
                new GenerationParams(0.2, 4096, null, 42L, Map.of(), List.of()));
        PromptSession session = new PromptSession(
                null, "research-agent", systemPrompt,
                Map.of(), List.of(), params, null);

        // Pull tools and providers via ServiceLoader so we can wrap each tool in
        // a TracingTool — gives us per-iteration visibility into what gemma is
        // actually fetching, which is the only way to debug a stuck ReAct loop.
        boolean trace = Boolean.parseBoolean(System.getProperty("trybunal.traceTools", "false"));
        var providers = new ArrayList<ModelProvider>();
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) providers.add(p);
        var tools = new ArrayList<Tool>();
        // Always register the in-process todo tool first so it shows up at the
        // top of the model's tool list. Smaller local models pay attention to
        // tool order in some prompt formats.
        Tool todo = new TodoTool();
        tools.add(trace ? new TracingTool(todo) : todo);
        for (Tool t : ServiceLoader.load(Tool.class)) {
            if (skipSet.contains(t.spec().name())) continue;
            tools.add(trace ? new TracingTool(t) : t);
        }
        if (!skipSet.isEmpty()) {
            System.out.println("Tools skipped via -Dtrybunal.skipTools: " + skipSet);
        }

        try (Orchestrator orch = Orchestrator.of(providers, List.of(), tools, maxIter)) {
            // Keep the user prompt SHORT. Diagnostics on gemma4:26b showed
            // that long system + long user + 2+ tool exchanges trip an
            // Ollama chat-template bug that returns the empty placeholder
            // {"model":"","done":false,...}. With a one-line prompt and the
            // todo tool to externalise the plan, gemma reliably progresses.
            String userPrompt = "Profile " + ticker + ". Plan with todo, "
                    + "fetch the EDGAR filings page, cite it, then summarise.";

            InvocationResult result = orch.chat(session, model, userPrompt);

            String body = result.reply().content();
            if (trace) {
                System.out.printf("[final] finish=%s contentLen=%d toolCalls=%d%n",
                        result.metadata().finishReason(), body == null ? 0 : body.length(),
                        result.reply().toolCalls().size());
                System.out.println("[final content]\n" + body);
            }
            String bib = CitationReport.renderMarkdown(CitationStore.shared().snapshot());
            String md = "# Research: " + ticker + "\n\n" + body + "\n\n" + bib + "\n";

            Path out = Path.of("build", "research-" + ticker + ".md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, md);
            System.out.println("Wrote " + out.toAbsolutePath());
            System.out.println("Tools registered: " + orch.registeredTools());
        }
    }

    /** Decorator that prints each invocation's args + result excerpt to stdout. */
    private static final class TracingTool implements Tool {
        private final Tool delegate;
        private final AtomicInteger n = new AtomicInteger();
        TracingTool(Tool delegate) { this.delegate = delegate; }
        @Override public ToolSpec spec() { return delegate.spec(); }
        @Override public ToolResult invoke(Map<String, Object> arguments) {
            int seq = n.incrementAndGet();
            System.out.printf("    [%s #%d] args=%s%n",
                    delegate.spec().name(), seq, oneLine(String.valueOf(arguments), 200));
            ToolResult r;
            try { r = delegate.invoke(arguments); }
            catch (RuntimeException e) {
                System.out.printf("    [%s #%d] EXCEPTION %s%n",
                        delegate.spec().name(), seq, e.getMessage());
                throw e;
            }
            System.out.printf("    [%s #%d] %s %s%n",
                    delegate.spec().name(), seq,
                    r.isError() ? "ERROR" : "ok",
                    oneLine(r.content(), 200));
            return r;
        }
        private static String oneLine(String s, int n) {
            if (s == null) return "";
            String x = s.replace('\n', ' ').replace('\r', ' ').trim();
            return x.length() <= n ? x : x.substring(0, n) + "…";
        }
    }
}
