package org.trybunal.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.core.Orchestrator;
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
        ModelId model = new ModelId("ollama", modelName);

        String today = LocalDate.now().toString();
        String systemPrompt = ("""
                You are a research analyst. Today is %TODAY%.

                Tools:
                - web_search(query, limit): find candidate sources
                - web_fetch(url, max_chars): read text from a URL
                - web_browser(url, wait_selector, wait_ms, max_chars): use only when web_fetch
                  returns a [js-heavy] prefix or fewer than 200 chars of text
                - safe_download(url, filename_hint, max_bytes): save PDFs / XLSX. ALWAYS pass
                  a concrete url; never call this tool with empty arguments.
                - cite(url, title, excerpt, sha256): record an excerpt before asserting any fact

                Recency rules:
                - Only cite filings or transcripts dated within the last 120 days from today.
                - If a candidate source is older, discard it and search again with a more
                  specific date qualifier (e.g. add the current quarter or year to the query).
                - Every cite(...) call MUST include the filing date in the title, e.g.
                  "AAPL 10-Q — filed 2026-04-30". If you don't know the date, fetch the page
                  and extract it before citing.

                Source priority (try in this order before falling back to generic search):
                  1. investor.<company>.com (investor relations site)
                  2. sec.gov EDGAR full-text search:
                     https://efts.sec.gov/LATEST/search-index?q=%22<TICKER>%22&forms=10-Q
                  3. Generic web_search as a last resort.

                Workflow: search → fetch → cite → optionally download. Cite EVERY claim.
                Stop when you have at least 3 cited claims (each with a date in the title)
                and a one-paragraph summary of the most recent quarter's highlights.
                """).replace("%TODAY%", today);

        PromptSession session = PromptSession.of("research-agent", systemPrompt);

        try (Orchestrator orch = Orchestrator.autoDiscover()) {
            String userPrompt = "Find the most recent quarterly earnings call transcript "
                    + "and 10-Q filing for ticker " + ticker + ". Summarise the highlights "
                    + "and download the 10-Q PDF if available.";

            InvocationResult result = orch.agent(session, model, userPrompt);

            String body = result.reply().content();
            String bib = CitationReport.renderMarkdown(CitationStore.shared().snapshot());
            String md = "# Research: " + ticker + "\n\n" + body + "\n\n" + bib + "\n";

            Path out = Path.of("build", "research-" + ticker + ".md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, md);
            System.out.println("Wrote " + out.toAbsolutePath());
            System.out.println("Tools registered: " + orch.registeredTools());
        }
    }
}
