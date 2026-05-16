# Investment-thesis information plan

> Scope: this file defines **what information a downstream "thesis writer"
> agent needs in order to draft an investment thesis on a public stock.**
> The job of the agents in this module is *only* to gather that information
> into well-organized, source-cited files on disk — not to produce the
> thesis itself.

A defensible investment thesis answers seven questions. Each question
maps to one research file under `build/thesis/<TICKER>/`, gathered by one
specialised sub-agent. Every file is markdown, every fact is cited, every
filing-date claim is dated.

## The seven sections

| # | File | Question the section must answer |
|---|---|---|
| 1 | `01-company-profile.md`     | What does the company actually do? Segments, revenue mix, geography, top customers/products, key management. |
| 2 | `02-financials.md`          | Most recent quarter's headline numbers (revenue, op. margin, EPS, FCF), 3-yr trend lines, and balance-sheet health (cash, debt, leverage). |
| 3 | `03-valuation.md`           | Current price, market cap, and the P/E, P/S, EV/EBITDA, FCF-yield multiples — *each compared to peers and to the company's own 5-yr range.* |
| 4 | `04-industry-and-peers.md`  | TAM size and growth, ranked peer list with market share, secular tailwinds and headwinds for the industry. |
| 5 | `05-moat-and-strategy.md`   | What is the durable competitive advantage (network effects / scale / IP / switching costs / brand)? Evidence from margin trend, retention, share trend. |
| 6 | `06-catalysts-and-risks.md` | Named near-term catalysts (next 4 quarters) and the top 5 risks, each with a primary-source citation. |
| 7 | `07-capital-allocation.md`  | Buyback history, dividend policy, M&A track record, insider transactions in the last 6 months. |

## Cross-cutting requirements (every file)

- **Recency**: any filing-derived number must come from a document filed within the last 120 days. Older numbers must be flagged as "stale".
- **Citations**: every numeric claim and every direct quote must be paired with a `cite(...)` call. The thesis writer agent will reject ungrounded numbers.
- **Date stamps**: cited filings must include their filing date in the title, e.g. `"AAPL 10-Q — filed 2026-04-30"`.
- **Source priority**: investor-relations site → SEC EDGAR → reputable financial news → generic search, in that order.
- **No conclusions**: gathering agents must not state "buy" or "sell". They produce facts and let the thesis writer reason.

## Stop conditions (per sub-agent)

Each sub-agent stops when its file contains:
1. A short bulleted answer to its question (≥ 4 bullets).
2. At least 3 distinct citations.
3. At least one citation dated within the last 120 days for the
   recency-sensitive sections (financials, valuation, catalysts).

That's it. The thesis writer reads these seven files and the shared
`citations.md` bibliography, then drafts.

## Subagent variant

`ThesisManager` (`./gradlew :examples:research-agent:thesisManager
--args="AAPL"`) is an alternative wiring of the same seven sections.
Instead of one flat ReAct loop per section it runs a *manager* model that
sees two subagents — registered as plain tools via
`org.trybunal.core.Subagents.asTool` — and decides per section how to
call them:

| Subagent    | Tools                                  | Job                                                                 |
|-------------|----------------------------------------|---------------------------------------------------------------------|
| `gather`    | `web_fetch`, `safe_download`, `cite`   | Collect cited facts for the section the manager hands it.           |
| `summarise` | (none)                                 | Turn the gathered notes into the final markdown for the section.    |

Each section becomes a manager turn: the manager calls `gather(task=...)`
to fetch facts, then `summarise(task=...)` with those facts to produce
the section markdown. The manager's conversation stays small because it
only sees each subagent's final string, not the tool calls underneath.

The subagent's ReAct cap is independent of the manager's
(`-Dtrybunal.workerMaxIter=N` overrides it). Both subagents share the
parent's `ModelProvider` instance — no extra HttpClient — and are
constructed in a try-with-resources block alongside the manager
orchestrator so their inner executors shut down deterministically when
the run ends.

Output shape is identical to `ThesisAgent`: one markdown file per
section under `build/thesis/<TICKER>/` plus the shared `citations.md`
bibliography.
