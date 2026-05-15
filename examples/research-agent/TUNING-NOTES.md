# Tuning notes — `ThesisEval`

This file records the rounds of tuning baked into `ThesisModelTuning`,
`ThesisRubrics`, and `ThesisEval`. Each round is one observation → one
change. Lessons inherited from `examples/agentic-eval/MODEL-LESSONS.md`
are credited inline rather than re-derived.

## Round 1 — token budgets

**Observation.** The default 1024-token budget is fine for the
agentic-readiness suite (single-shot prompts) but starves an agent loop:
each tool round-trip costs 200–600 tokens before the model has produced
any visible content, and we routinely make 3–5 round-trips per section.

**Change.** Default raised to 4096 in `ThesisAgent.DEFAULT_MAX_TOKENS`
and `ThesisEval.sessionFor(...)`. Per-model bumps in
`ThesisModelTuning`:

| Model | maxTokens | Why |
|---|---|---|
| `gpt-oss:20b` | 8192 | Harmony channel — agentic-eval lessons |
| `phi4-reasoning:latest` | 8192 | `<think>` blocks compound per turn |
| `gemma4:26b` | 8192 | Thinking channel routed before content |
| `mistral-small:24b` | 6144 | Headroom for long sections (industry, catalysts) |
| `phi4:14b` | 6144 | Same; emits longer prose by default |
| (else) | 4096 | Llama 3.1 / smaller — fits comfortably |

## Round 2 — rubric strictness

**Observation.** First draft of the cross-cutting checks demanded
*exactly* 3 citations and a specific URL structure. Several real
candidate runs failed not because they under-cited, but because they
over-cited, and the regex-style anchors in checklist items confused the
judge.

**Change.** All quantity checks reworded as `"at least N"`. Removed the
URL-structure check and replaced with a softer "appears to be a primary
source — SEC filing, IR site, or reputable financial news domain".

## Round 3 — no-conclusion guard

**Observation.** Even with a system-prompt rule against `buy`/`sell`
language, 24 B-class models slipped editorialising into the valuation
section ("trading at a premium to peers"). The thesis-writer agent
downstream is supposed to do that work; if the gatherer pre-empts it,
the eventual thesis is less defensible.

**Change.** Added an explicit cross-cutting check forbidding the
recommendation vocabulary, and a section-specific check on
`03-valuation` reinforcing it. `buyback` is allowlisted because it is a
factual term, not a recommendation.

## Round 4 — recency phrasing

**Observation.** The original recency rule referenced "the most recent
quarter" without a date constraint. Older models cheerfully cited
filings from 2023 because *that* was their most recent quarter from
training data. The recency-rule has to be wall-clock-grounded.

**Change.** System prompt now ties recency to "the last 120 days from
today" with `%TODAY%` substituted at runtime. The rubric check on
`02-financials` requires the cited filing date to "appear to be within
roughly the last 4–5 months" — a soft check the judge can apply
without pretending to know today's exact date.

## Round 5 — concurrency

**Observation.** `Orchestrator.evaluateAll` runs cases on a
virtual-thread executor by design. With seven sections each issuing
3–5 web searches, the configured DuckDuckGo provider tripped its
soft rate limit halfway through the first model run.

**Change.** `ThesisEval.evaluateSequential` drives one case at a time.
The trade is wall time vs. reliability — and since Ollama can only hold
one model in VRAM at once anyway, parallelism across cases for a single
model never helped throughput.

## Round 6 — judge separation (open)

Self-judging works as a baseline but is biased — see the agentic-eval
cross-judge results. `-Dtrybunal.judge=` is wired through
`ThesisEval.main` so a heterogeneous judge can be specified, but a
fully cross-graded run (every model judged by every other model) is
left for a follow-up; the rubric checklists reduce — but do not
eliminate — the bias problem on their own.

## Round 7 — first multi-model run, AAPL, judge=`phi4:14b`

Live results: `gemma4:26b`, `gpt-oss:20b`, `phi4-reasoning:latest`,
`phi4:14b`, `llama3.1:8b`, `mistral-small:24b`. **0/7 across the
board.** Reading the per-section markdown turned up four distinct
problems, not one:

1. **`MAX_TOOL_ITERATIONS = 8` is the binding constraint for
   harmony / thinking models.** `gpt-oss:20b` hit the cap on **7/7**
   sections; `gemma4:26b` on **6/7**. Each thinking-channel turn costs
   an iteration before any tool call lands.
   *Fix:* out of module scope — needs a phase task to make the cap
   configurable on `Orchestrator`.

2. **`phi4` and `phi4-reasoning` don't support tools on Ollama.** Both
   returned a 400 immediately with the body
   `"<model> does not support tools"`. They're still fine as judges.
   *Fix applied:* `ThesisEval.NON_TOOL_TARGETS` skips them as targets
   with a clear message.

3. **`mistral-small:24b` emits raw `[TOOL_CALLS]…` text instead of
   structured tool calls.** The Ollama provider doesn't recognise that
   format, so the "tool call" leaks through to the final reply,
   nothing dispatches, and the model invents `[REF]N[/REF]` markers
   pretending it had results.
   *Fix:* out of module scope — needs work on the Ollama provider's
   tool-call extractor. Mitigated locally by adding a rubric check
   that flags invented `[REF]` markers.

4. **`llama3.1:8b` cites stale, non-primary sources.** Best of the
   tool-using lot at 6–7 of 8/9 per section, but happily cited
   2022/2023 filings (today is 2026-05-10) and Investopedia / Seeking
   Alpha / TipRanks / Benzinga as if they were primary.
   *Fix applied:* tightened the system prompt with a hard 90-day
   recency cutoff, an explicit primary-domain preference list, and an
   explicit aggregator-rejection list.

### Rubric tuning from this run

- "≥3 distinct external URLs" → "≥2", with explicit aggregator
  exclusion. Three URLs was the bar for runs where citations
  resolved cleanly; with the harness eating Mistral's citations the
  threshold was punishing tool-call format mismatch, not gathering
  quality.
- Removed the standalone "no buy/sell language" cross-cutting check
  — judges occasionally tripped on legitimate phrases like
  "buyback authorization" despite the allowlist. The valuation
  section keeps its targeted version, which is where editorialising
  actually matters.
- Added a "no invented citation markers" check — `[REF]1[/REF]` /
  `[1]` without resolution is the dominant failure mode when the
  harness silently drops tool calls.

### One judge-parser oddity to track

`llama3.1:8b · 05-moat-and-strategy` came back with
`"Judge returned no JSON block. Raw: {"results":[…valid JSON…]}"`
— the JSON was right there, the extractor missed it. Not a rubric
problem; track separately as a hardening pass on
`JudgePromptTemplate.extractJsonBlock`.

## Things explicitly NOT done

- No bespoke `Evaluator` SPI. The existing `LlmRubricChecklist` is the
  right primitive here; adding a new evaluator would be premature.
- No tool-call-count metric. The harness already logs tool dispatch;
  pulling it into the verdict is out of scope for "did we gather the
  right facts."
- No domain-specific prompt-templating beyond ticker + date. Per-sector
  tuning (banks vs. SaaS vs. miners) is plausible future work but
  shouldn't ship before we have a baseline run on a single ticker.
