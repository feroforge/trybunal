package org.trybunal.examples.toolsmoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.trybunal.tool.browser.BrowserTool;
import org.trybunal.tool.citations.CiteTool;
import org.trybunal.tool.download.SafeDownloadTool;
import org.trybunal.tool.mocks.MockCiteTool;
import org.trybunal.tool.mocks.MockSafeDownloadTool;
import org.trybunal.tool.mocks.MockTools;
import org.trybunal.tool.mocks.MockWebBrowserTool;
import org.trybunal.tool.mocks.MockWebFetchTool;
import org.trybunal.tool.mocks.MockWebSearchTool;
import org.trybunal.tool.webfetch.WebFetchTool;
import org.trybunal.tool.websearch.WebSearchTool;

/**
 * Per-tool agentic-loop smoke test for a single Ollama model.
 *
 * <p>For each requested tool, this runs an isolated {@link Orchestrator} with
 * <em>only that tool registered</em>, sends a prompt designed to elicit one
 * call to it, and prints whether the model emitted a tool call, whether the
 * tool dispatched, and the final reply.</p>
 *
 * <pre>
 *   ./gradlew :examples:tool-smoke:run \
 *       -Dtrybunal.model=gemma4:26b \
 *       -Dtrybunal.tool=web_search    # or web_fetch|web_browser|safe_download|cite|all
 *       -Dtrybunal.maxTokens=4096
 *       -Dtrybunal.thinking=true       # set false to send think:false to gemma/gpt-oss
 *       -Dtrybunal.maxIter=8
 * </pre>
 */
public final class ToolSmoke {

    private ToolSmoke() {}

    public static void main(String[] args) {
        String modelName = sysProp("trybunal.model", "gemma4:26b");
        String toolName  = sysProp("trybunal.tool",  "web_search");
        int maxTokens    = parseInt(sysProp("trybunal.maxTokens", "4096"), 4096);
        boolean thinking = Boolean.parseBoolean(sysProp("trybunal.thinking", "true"));
        int maxIter      = parseInt(sysProp("trybunal.maxIter", "8"), 8);
        ModelId model = new ModelId("ollama", modelName);

        ModelProvider ollama = locateProvider("ollama");
        if (ollama == null) {
            System.err.println("no ollama provider on classpath");
            System.exit(2);
            return;
        }

        List<String> requested = toolName.equalsIgnoreCase("all")
                ? List.of("web_search", "web_fetch", "web_browser", "safe_download", "cite")
                : List.of(toolName.toLowerCase(Locale.ROOT));

        System.out.printf("model=%s thinking=%s maxTokens=%d maxIter=%d mocks=%s%n",
                modelName, thinking, maxTokens, maxIter, MockTools.enabled());
        System.out.println("=".repeat(72));

        for (String t : requested) {
            runOne(t, model, ollama, maxTokens, thinking, maxIter);
            System.out.println();
        }
    }

    private static void runOne(String toolName, ModelId model, ModelProvider ollama,
                                int maxTokens, boolean thinking, int maxIter) {
        Scenario scenario = scenarioFor(toolName);
        if (scenario == null) {
            System.out.println("[skip] unknown tool: " + toolName);
            return;
        }

        CountingTool counted = new CountingTool(scenario.tool);
        Map<String, Object> extras = new LinkedHashMap<>();
        if (!thinking) extras.put("think", Boolean.FALSE);
        GenerationParams params = new GenerationParams(
                0.0, maxTokens, null, 42L, extras, List.of());

        PromptSession session = new PromptSession(
                null, "tool-smoke-" + toolName, scenario.systemPrompt,
                Map.of(), List.of(), params, null);

        long t0 = System.currentTimeMillis();
        InvocationResult result;
        String finish;
        String origin;
        String content;
        try (Orchestrator orch = Orchestrator.of(
                List.of(ollama), List.of(), List.of(counted), maxIter)) {
            result = orch.chat(session, model, scenario.userPrompt);
            finish = String.valueOf(result.metadata().finishReason());
            Object o = result.metadata().providerExtras().get("tool_call_origin");
            origin = o == null ? "—" : o.toString();
            content = result.reply().content() == null ? "" : result.reply().content();
        } catch (RuntimeException e) {
            System.out.printf("[%s] ERROR %s%n", toolName, truncate(e.getMessage(), 200));
            return;
        }
        long elapsed = System.currentTimeMillis() - t0;

        boolean dispatched = counted.calls() > 0;
        boolean errored    = counted.errors() > 0;
        String verdict;
        if (dispatched && !errored) verdict = "PASS";
        else if (dispatched)        verdict = "DISPATCHED-WITH-ERROR";
        else                         verdict = "NO-CALL";

        System.out.printf("[%s] %s  calls=%d errors=%d finish=%s origin=%s %dms%n",
                toolName, verdict, counted.calls(), counted.errors(), finish, origin, elapsed);
        if (!counted.lastArgs.isEmpty()) {
            System.out.println("    last-args: " + truncate(counted.lastArgs.toString(), 240));
        }
        if (!counted.lastResult.isEmpty()) {
            System.out.println("    last-result: " + truncate(counted.lastResult, 240));
        }
        System.out.println("    reply: " + truncate(content.replace('\n', ' '), 240));
    }

    /** A {@link Tool} that delegates to another tool and counts invocations. */
    private static final class CountingTool implements Tool {
        private final Tool delegate;
        private final AtomicInteger calls = new AtomicInteger(0);
        private final AtomicInteger errors = new AtomicInteger(0);
        private volatile Map<String, Object> lastArgs = Map.of();
        private volatile String lastResult = "";

        CountingTool(Tool delegate) { this.delegate = delegate; }

        @Override public ToolSpec spec() { return delegate.spec(); }

        @Override public ToolResult invoke(Map<String, Object> arguments) {
            calls.incrementAndGet();
            lastArgs = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
            ToolResult r;
            try {
                r = delegate.invoke(arguments);
            } catch (RuntimeException e) {
                errors.incrementAndGet();
                lastResult = "exception: " + e.getMessage();
                throw e;
            }
            if (r.isError()) errors.incrementAndGet();
            lastResult = r.content();
            return r;
        }

        int calls() { return calls.get(); }
        int errors() { return errors.get(); }
    }

    /** A model+user prompt + the tool to register. */
    private record Scenario(String systemPrompt, String userPrompt, Tool tool) {}

    private static Scenario scenarioFor(String name) {
        boolean mocks = MockTools.enabled();
        return switch (name) {
            case "web_search" -> new Scenario(
                    "You are a research assistant with access to a single tool, web_search. "
                            + "When the user asks anything that requires current information, "
                            + "call web_search exactly once with a focused query, then briefly "
                            + "summarise the results.",
                    "What were the top search results today for \"Apple Q2 2026 earnings transcript\"? "
                            + "Use web_search to find them.",
                    mocks ? new MockWebSearchTool() : new WebSearchTool());
            case "web_fetch" -> new Scenario(
                    "You are a research assistant with access to a single tool, web_fetch. "
                            + "It downloads the text of a URL. Call it exactly once on the URL "
                            + "the user provides, then briefly summarise the page.",
                    "Fetch https://example.com and tell me what's on the page.",
                    mocks ? new MockWebFetchTool() : new WebFetchTool());
            case "web_browser" -> new Scenario(
                    "You are a research assistant with access to a single tool, web_browser. "
                            + "It renders a URL in a headless browser and returns the rendered "
                            + "text. Call it exactly once on the URL the user provides, then "
                            + "summarise.",
                    "Render https://example.com with web_browser and summarise the headline.",
                    mocks ? new MockWebBrowserTool() : new BrowserTool());
            case "safe_download" -> new Scenario(
                    "You are a research assistant with access to a single tool, safe_download. "
                            + "It saves a remote file to a sandbox and returns a Source record. "
                            + "When the user gives you a PDF URL, call safe_download exactly once "
                            + "with that url, then report the local path and sha256.",
                    "Download https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf "
                            + "using safe_download and tell me the sha256 it returned.",
                    mocks ? new MockSafeDownloadTool() : new SafeDownloadTool());
            case "cite" -> new Scenario(
                    "You are a research assistant with access to a single tool, cite. "
                            + "Call cite exactly once with the url, title, excerpt, and sha256 the "
                            + "user provides, then confirm what you recorded.",
                    "Record a citation with these fields and then confirm:\n"
                            + "  url: https://example.com\n"
                            + "  title: Example Domain\n"
                            + "  excerpt: This domain is for use in illustrative examples in documents.\n"
                            + "  sha256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    mocks ? new MockCiteTool() : new CiteTool());
            default -> null;
        };
    }

    private static ModelProvider locateProvider(String id) {
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
            if (id.equals(p.id())) return p;
        }
        return null;
    }

    private static String sysProp(String key, String def) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= n ? oneLine : oneLine.substring(0, n) + "…";
    }
}
