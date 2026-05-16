package org.trybunal.examples.toolsmoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.trybunal.tool.browser.BrowserTool;
import org.trybunal.tool.citations.CiteTool;
import org.trybunal.tool.download.SafeDownloadTool;
import org.trybunal.tool.webfetch.WebFetchTool;
import org.trybunal.tool.websearch.WebSearchTool;

/**
 * Single-task agentic run that requires gemma4 (or any model) to use every
 * Phase 4 tool. Each tool is wrapped in a {@link RetryingCountingTool} that
 * retries transient errors silently before surfacing the result to the
 * model; a final summary reports per-tool attempts and whether any tool
 * still failed persistently after exhausting retries.
 *
 * <pre>
 *   ./gradlew :examples:tool-smoke:run -PmainClass=AllTools \
 *       -Dtrybunal.model=gemma4:26b -Dtrybunal.maxTokens=8192
 * </pre>
 *
 * <p>Use the {@code allTools} Gradle task wired in build.gradle.kts.</p>
 */
public final class AllTools {

    private AllTools() {}

    private static final String PDF_URL =
            "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";
    private static final String PAGE_URL = "https://example.com";

    public static void main(String[] args) {
        String modelName = sysProp("trybunal.model", "gemma4:26b");
        int maxTokens    = parseInt(sysProp("trybunal.maxTokens", "8192"), 8192);
        boolean thinking = Boolean.parseBoolean(sysProp("trybunal.thinking", "true"));
        int maxIter      = parseInt(sysProp("trybunal.maxIter", "12"), 12);
        int retries      = parseInt(sysProp("trybunal.retries",  "3"), 3);
        long retryDelay  = parseLong(sysProp("trybunal.retryDelayMs", "1500"), 1500);
        ModelId model = new ModelId("ollama", modelName);

        ModelProvider ollama = locateProvider("ollama");
        if (ollama == null) {
            System.err.println("no ollama provider on classpath");
            System.exit(2);
            return;
        }

        List<RetryingCountingTool> wrapped = List.of(
                new RetryingCountingTool(new WebSearchTool(),    retries, retryDelay),
                new RetryingCountingTool(new WebFetchTool(),     retries, retryDelay),
                new RetryingCountingTool(new BrowserTool(),      retries, retryDelay),
                new RetryingCountingTool(new SafeDownloadTool(), retries, retryDelay),
                new RetryingCountingTool(new CiteTool(),         retries, retryDelay)
        );
        List<Tool> tools = new ArrayList<>(wrapped);

        String system = """
                You are a research assistant building a single citation record for a test PDF.
                You have access to five tools: web_search, web_fetch, web_browser,
                safe_download, and cite. You MUST call EACH of these five tools at least
                once during this conversation. Do not skip a step because a previous
                step "already gave you the answer" — each step has an audit purpose.

                Execute these steps in order; emit one tool call per step.

                  1. web_search — search the web for "W3C WAI ER dummy.pdf test file".
                  2. web_fetch — fetch the linking page %PAGE%.
                  3. web_browser — MANDATORY. Render %PAGE% with web_browser. Do not
                     skip this step. We need the browser-rendered text in the audit
                     trail regardless of what step 2 already returned.
                  4. safe_download — download the PDF at %PDF%. The tool's result
                     line contains "sha256=<hash>"; copy that 64-character hash
                     EXACTLY for step 5.
                  5. cite — record a citation with:
                        url:     %PDF%
                        title:   Dummy PDF Example
                        excerpt: Dummy PDF file
                        sha256:  the 64-hex hash returned by step 4

                After all five tool calls have completed successfully, write a 2-3
                sentence summary. Do NOT write the summary before calling all five
                tools — the summary will be rejected if any of the five tools has
                not been called.
                """
                .replace("%PAGE%", PAGE_URL)
                .replace("%PDF%",  PDF_URL);

        Map<String, Object> extras = new LinkedHashMap<>();
        if (!thinking) extras.put("think", Boolean.FALSE);
        GenerationParams params = new GenerationParams(
                0.0, maxTokens, null, 42L, extras, List.of());

        PromptSession session = new PromptSession(
                null, "all-tools", system, Map.of(), List.of(), params, null);

        String user = "Build the citation record described in the system prompt. "
                + "Use every tool exactly once, in the order listed.";

        System.out.printf("model=%s thinking=%s maxTokens=%d maxIter=%d retries=%d%n",
                modelName, thinking, maxTokens, maxIter, retries);
        System.out.println("=".repeat(76));

        long t0 = System.currentTimeMillis();
        InvocationResult result;
        try (Orchestrator orch = Orchestrator.of(
                List.of(ollama), List.of(), tools, maxIter)) {
            result = orch.chat(session, model, user);
        } catch (RuntimeException e) {
            System.out.println("RUN ERRORED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("--- per-tool summary ---");
        boolean allPassed = true;
        boolean anyPersistent = false;
        for (RetryingCountingTool t : wrapped) {
            String name = t.spec().name();
            String verdict;
            if (t.successes() > 0 && !t.lastErrorPersistent) {
                verdict = "OK";
            } else if (t.successes() == 0 && t.totalAttempts() == 0) {
                verdict = "NOT-CALLED";
                allPassed = false;
            } else if (t.lastErrorPersistent) {
                verdict = "PERSISTENT-FAILURE";
                allPassed = false;
                anyPersistent = true;
            } else {
                verdict = "?";
                allPassed = false;
            }
            System.out.printf("  %-14s %-20s attempts=%d successes=%d retries=%d%n",
                    name, verdict, t.totalAttempts(), t.successes(), t.totalRetries());
            if (!t.lastErrorMessage.isEmpty()) {
                System.out.println("      last-error: " + truncate(t.lastErrorMessage, 200));
            }
        }
        System.out.println();
        System.out.printf("VERDICT: %s  (finish=%s, %dms)%n",
                allPassed ? "ALL TOOLS SUCCESSFUL"
                          : (anyPersistent ? "PERSISTENT FAILURE" : "TOOL MISSING"),
                result.metadata().finishReason(), elapsed);
        System.out.println();
        System.out.println("--- model reply ---");
        System.out.println(result.reply().content());
    }

    /**
     * Wraps a {@link Tool} with silent retries: if the underlying tool returns
     * {@link ToolResult#isError()} or throws, retry up to {@code retries}
     * times with a short backoff before surfacing the last result. The model
     * never sees the retried errors.
     */
    private static final class RetryingCountingTool implements Tool {
        private final Tool delegate;
        private final int retries;
        private final long delayMs;
        private final AtomicInteger totalAttempts = new AtomicInteger();
        private final AtomicInteger totalRetries = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        volatile String lastErrorMessage = "";
        volatile boolean lastErrorPersistent;

        RetryingCountingTool(Tool delegate, int retries, long delayMs) {
            this.delegate = delegate;
            this.retries  = Math.max(0, retries);
            this.delayMs  = Math.max(0, delayMs);
        }

        @Override public ToolSpec spec() { return delegate.spec(); }

        @Override public ToolResult invoke(Map<String, Object> arguments) {
            ToolResult last = null;
            String lastErr = "";
            int attempts = 0;
            for (int attempt = 0; attempt <= retries; attempt++) {
                attempts++;
                totalAttempts.incrementAndGet();
                if (attempt > 0) totalRetries.incrementAndGet();
                try {
                    last = delegate.invoke(arguments);
                    if (!last.isError()) {
                        successes.incrementAndGet();
                        lastErrorMessage = "";
                        lastErrorPersistent = false;
                        return last;
                    }
                    lastErr = last.content();
                } catch (RuntimeException e) {
                    lastErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                    last = ToolResult.error(lastErr);
                }
                if (attempt < retries) {
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            lastErrorMessage = lastErr;
            lastErrorPersistent = true;
            return last != null ? last : ToolResult.error("unknown failure after " + attempts + " attempts");
        }

        int totalAttempts() { return totalAttempts.get(); }
        int totalRetries()  { return totalRetries.get(); }
        int successes()     { return successes.get(); }
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

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= n ? oneLine : oneLine.substring(0, n) + "…";
    }
}
