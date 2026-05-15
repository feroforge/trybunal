package org.trybunal.examples.thesis;

import java.util.List;

/**
 * The seven research sections that, together, give a thesis-writing agent
 * everything it needs to draft an investment thesis on a public stock.
 *
 * <p>Each {@link Section} carries the slug used for the output file, a
 * one-line question, and the user-prompt template fed to the gathering
 * sub-agent. Templates contain the literal {@code %TICKER%} marker which
 * {@link #renderUserMessage} replaces.</p>
 *
 * <p>The shared {@link #SYSTEM_PROMPT} is the contract every sub-agent
 * obeys: source priority, recency, citations, no-conclusions. It mirrors
 * the requirements in {@code THESIS-PLAN.md}; if you edit one, edit
 * both.</p>
 */
public final class ThesisSections {

    private ThesisSections() {}

    /**
     * One sub-agent's job. {@code slug} is the filename root
     * ({@code 01-company-profile} → {@code 01-company-profile.md}); the
     * leading two-digit prefix orders the files on disk.
     */
    public record Section(String slug, String question, String userPromptTemplate) {
        public Section {
            if (slug == null || slug.isBlank())
                throw new IllegalArgumentException("slug required");
            if (question == null || question.isBlank())
                throw new IllegalArgumentException("question required");
            if (userPromptTemplate == null || userPromptTemplate.isBlank())
                throw new IllegalArgumentException("userPromptTemplate required");
        }
    }

    /**
     * Shared system prompt. {@code %TODAY%} is substituted at runtime by
     * {@link ThesisAgent}. Recency rules and source priority match
     * {@code THESIS-PLAN.md}. The "no conclusions" line is critical:
     * gathering agents must not pre-empt the thesis writer.
     */
    public static final String SYSTEM_PROMPT = ("""
            You are a junior equity-research analyst. Today is %TODAY%.
            You are gathering information for ONE section of an investment
            thesis on ticker %TICKER%. A different agent will write the
            thesis; you only assemble facts.

            Tools:
            - web_search(query, limit)        find candidate sources
            - web_fetch(url, max_chars)       read text from a URL
            - web_browser(url, ...)           use only when web_fetch returns
                                              [js-heavy] or <200 chars
            - safe_download(url, ...)         save filings (PDF/XLSX). Always
                                              pass a concrete url.
            - cite(url, title, excerpt, ...)  record a quoted excerpt before
                                              you assert any fact based on it

            Source priority (try in this order):
              1. investor.<company>.com (IR site)
              2. sec.gov EDGAR full-text search:
                 https://efts.sec.gov/LATEST/search-index?q=%22%TICKER%%22&forms=10-Q
              3. Reputable financial news (Reuters, Bloomberg, FT, WSJ).
              4. Generic web_search as a last resort.

            Recency rules:
            - Hard cutoff: do NOT use any source dated more than 90 days
              before today. If your search returns older results, search
              again with a date qualifier (current quarter / year added).
            - Every cite(...) for a filing MUST include its filing date in
              the title, e.g. "%TICKER% 10-Q — filed 2026-04-30".
            - When in doubt about a number's recency, say "as of <date>"
              rather than presenting it as current.

            Citation rules:
            - Use the cite(url, title, excerpt, ...) tool. Do NOT invent
              your own [REF]N[/REF] / [1] / footnote markers — the
              downstream agent reads the cite() store, not body text.
            - When ranking search results, prefer URLs whose host or path
              contains: "investor.", "sec.gov", "edgar", "/10-q", "/10-k",
              "/press-release", "/earnings", "ir.<company>". Fall through
              to news domains only when no primary source is available.
            - Reuters / Bloomberg / FT / WSJ are acceptable secondaries.
              Investopedia / Seeking Alpha / TipRanks / Benzinga / Motley
              Fool are NOT acceptable as a primary source for any number.

            Output rules:
            - Reply with markdown ONLY — no preamble, no apology.
            - Cite every numeric claim and every direct quote.
            - You are gathering facts. Do not recommend an action.
            - Stop when you have at least 3 cited claims (≥1 dated within
              the last 90 days for recency-sensitive sections).
            """).stripIndent();

    /** The seven sections, in display order. Defensively immutable. */
    public static final List<Section> ALL = List.of(
            new Section("01-company-profile",
                    "What does the company actually do?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Company profile".
                    Cover, in this order:
                      1. One-paragraph plain-English description of the business.
                      2. Reportable segments with revenue share %% (latest fiscal
                         year). Cite the 10-K or latest 10-Q.
                      3. Top 3 products/services or business lines and what
                         each contributes to revenue.
                      4. Geographic revenue mix (Americas / EMEA / APAC etc).
                      5. CEO and CFO names with tenure.
                    Stop after at least 3 citations, at least one to a primary
                    filing (10-K or 10-Q) on sec.gov or the IR site.
                    """),

            new Section("02-financials",
                    "What are the latest quarterly financials and trend?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Financials".
                    Cover:
                      1. Most recent quarter: revenue, YoY growth, operating
                         margin, GAAP EPS, free cash flow. Cite the 10-Q or
                         8-K earnings release filed within 120 days.
                      2. 3-year trend table (most recent FY and the two
                         before): revenue, op. margin, FCF.
                      3. Balance sheet at last quarter-end: cash &
                         equivalents, total debt, net debt, current ratio.
                      4. One sentence on the quarter's highlight and one on
                         the lowlight, each supported by a cited quote from
                         the earnings call or release.
                    Numbers must be exact. If a number is unavailable, write
                    "unavailable" — do NOT estimate.
                    """),

            new Section("03-valuation",
                    "How is the stock priced today, absolute and relative?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Valuation".
                    Cover:
                      1. Current price and market cap (cite source with date).
                      2. Trailing P/E, forward P/E, P/S, EV/EBITDA, FCF yield.
                      3. Same multiples for 2 named peers in the same industry.
                      4. Each multiple compared to the company's own 5-year
                         median (call it "5y median: X" — cite a screener or
                         data provider).
                    Do NOT call the stock cheap or expensive. Just assemble
                    the numbers with citations.
                    """),

            new Section("04-industry-and-peers",
                    "What does the industry and peer group look like?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Industry & peers".
                    Cover:
                      1. Industry definition in one sentence.
                      2. TAM and projected industry growth rate, with a cited
                         source (analyst report, government stats, IR deck).
                      3. Ranked list of the top 4–6 peers with approximate
                         market share and one-line differentiation per peer.
                      4. Two named tailwinds and two named headwinds for the
                         industry, each citing a primary article or filing.
                    """),

            new Section("05-moat-and-strategy",
                    "What is the durable competitive advantage?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Moat & strategy".
                    Cover:
                      1. Classify the moat: one or more of {network effects,
                         scale, intangible assets/IP, switching costs, brand,
                         efficient scale, no clear moat}. Defend the
                         classification in 2 sentences with citations.
                      2. Three pieces of moat evidence: gross-margin trend,
                         net-revenue-retention or churn metric, market-share
                         trend. Cite each.
                      3. The company's stated strategic priorities for the
                         next 12 months — verbatim quote from the most recent
                         earnings call or shareholder letter, with citation.
                    """),

            new Section("06-catalysts-and-risks",
                    "What moves the stock in the next four quarters?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Catalysts & risks".
                    Cover:
                      1. Three named near-term catalysts (next 4 quarters):
                         product launches, regulatory decisions, contract
                         awards, earnings inflections. For each, cite a
                         primary source dated within 120 days.
                      2. Top five risks, each one sentence with a cited
                         source. Pull from the "Risk Factors" section of the
                         most recent 10-K or 10-Q where possible.
                      3. Pending litigation or regulatory action of material
                         size, if any.
                    """),

            new Section("07-capital-allocation",
                    "How does management deploy cash?",
                    """
                    Produce a markdown brief titled "# %TICKER% — Capital allocation".
                    Cover:
                      1. Dividend: current $/sh, yield, last raise date and
                         amount, payout ratio. Cite the IR site.
                      2. Buybacks: $ repurchased over the last 4 quarters,
                         current authorisation remaining. Cite the 10-Q.
                      3. M&A in the last 24 months: list of deals with size
                         and rationale, citing the 8-K announcement.
                      4. Insider transactions in the last 6 months —
                         aggregate net bought / sold, biggest single trade.
                         Cite the SEC Form 4 filings via EDGAR.
                    """)
    );

    /** Substitutes {@code %TICKER%} into a section's user-prompt template. */
    public static String renderUserMessage(Section s, String ticker) {
        return s.userPromptTemplate().replace("%TICKER%", ticker);
    }

    /** Substitutes {@code %TICKER%} and {@code %TODAY%} into the system prompt. */
    public static String renderSystemPrompt(String ticker, String today) {
        return SYSTEM_PROMPT
                .replace("%TICKER%", ticker)
                .replace("%TODAY%", today);
    }
}
