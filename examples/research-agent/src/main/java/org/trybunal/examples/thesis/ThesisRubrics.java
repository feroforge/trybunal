package org.trybunal.examples.thesis;

import java.util.ArrayList;
import java.util.List;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationCriteria.TextMatch.Regex.MatchMode;
import org.trybunal.api.model.ModelId;

/**
 * Builds the seven-case evaluation suite for the gathering sub-agent.
 *
 * <p>Each case wraps one {@link ThesisSections.Section}. Two evaluators
 * grade the candidate output, both materialised as a single
 * {@link EvaluationCriteria.LlmRubricChecklist}:</p>
 *
 * <ol>
 *   <li><b>Cross-cutting checks</b> shared by every section — does the
 *       output cite at least three distinct URLs, does it stay out of
 *       buy/sell territory, does it respect the markdown-only rule.</li>
 *   <li><b>Section-specific checks</b> derived from the section's
 *       acceptance bullets in {@code THESIS-PLAN.md} — segment table for
 *       the profile case, multiples for valuation, etc.</li>
 * </ol>
 *
 * <p>A single checklist gives a per-check verdict from the judge in one
 * inference, which is more reliable than a free-text rubric (see
 * {@code EvaluationCriteria.LlmRubricChecklist} javadoc). The case passes
 * iff every check is true; partial credit shows up as
 * {@code score = passingChecks / totalChecks} on the verdict.</p>
 *
 * <p>A separate {@link #structuralRegex} returns a deterministic
 * {@code TextMatch.Regex} guard you can run alongside the LLM rubric in
 * tooling that wants a cheap pre-filter. It currently only checks for the
 * mandatory {@code "# <TICKER> — <Title>"} top-line header.</p>
 */
final class ThesisRubrics {

    private ThesisRubrics() {}

    /**
     * Builds one {@link EvaluationCase} per section. Cases use the
     * {@code section.userPromptTemplate()} verbatim (with {@code %TICKER%}
     * substituted) so the candidate run during evaluation is identical to
     * what {@link ThesisAgent} would dispatch.
     */
    static List<EvaluationCase> buildSuite(String ticker, ModelId judge) {
        var out = new ArrayList<EvaluationCase>();
        for (ThesisSections.Section section : ThesisSections.ALL) {
            String userMsg = ThesisSections.renderUserMessage(section, ticker);
            List<String> checks = new ArrayList<>();
            checks.addAll(crossCuttingChecks(ticker));
            checks.addAll(sectionChecks(section, ticker));
            out.add(new EvaluationCase(
                    section.slug(),
                    userMsg,
                    new EvaluationCriteria.LlmRubricChecklist(checks, judge)));
        }
        return List.copyOf(out);
    }

    /**
     * Structural pre-filter: does the candidate start with the mandatory
     * markdown header? Cheap to run before paying for a judge inference.
     */
    static EvaluationCriteria.TextMatch.Regex structuralRegex(String ticker, String title) {
        // "# AAPL — Company profile" — em-dash is intentional; keep it
        // matching exactly what the user-prompt asks for.
        String pat = "(?m)^#\\s+" + java.util.regex.Pattern.quote(ticker)
                + "\\s+[—-]\\s+" + java.util.regex.Pattern.quote(title);
        return new EvaluationCriteria.TextMatch.Regex(pat, MatchMode.FIND);
    }

    // ── Cross-cutting checks (apply to every section) ────────────────────

    private static List<String> crossCuttingChecks(String ticker) {
        // Tuned after round-1 multi-model run (see TUNING-NOTES.md):
        //   * URL count lowered 3 → 2 — over-counted runs where the harness
        //     swallowed cite() output but the body still had real sources.
        //   * Standalone buy/sell check removed; the only section where
        //     it bites (valuation) keeps a section-specific version.
        //   * Added an explicit "no invented citation markers" check — this
        //     was the dominant failure mode for mistral-small:24b.
        return List.of(
                "The candidate output is well-formed markdown — it begins with a level-1 heading and contains no preamble or apology before that heading.",
                "The candidate output mentions ticker '" + ticker + "' (or the company name it stands for) explicitly at least once.",
                "The candidate output references at least 2 distinct external URLs (http or https), each appearing to be a primary source — SEC filing, IR site, or reputable financial news domain (Reuters / Bloomberg / FT / WSJ). Aggregator domains alone (Investopedia, Seeking Alpha, TipRanks, Benzinga, Motley Fool) do NOT count.",
                "The candidate output does NOT contain bare invented-citation markers like '[REF]1[/REF]', '[1]' / '[2]' without resolution, or '[Source: Apple]' that don't correspond to actual URLs anywhere in the output. References must point to something concrete.",
                "Every numeric financial claim in the candidate (revenue, margin, EPS, cash, debt, share price, growth rate) is accompanied either by an inline citation/link or by an unambiguous source reference in the same paragraph."
        );
    }

    // ── Section-specific checks ──────────────────────────────────────────

    private static List<String> sectionChecks(ThesisSections.Section section, String ticker) {
        return switch (section.slug()) {
            case "01-company-profile" -> List.of(
                    "The output names at least 2 reportable business segments (or 'single segment' is explicitly stated and supported with a citation to a 10-K).",
                    "The output identifies the CEO and CFO by name.",
                    "The output gives at least one number for geographic revenue split (e.g. 'Americas 42%') or explicitly states the company does not break out geography.");

            case "02-financials" -> List.of(
                    "The output reports the most recent quarter's revenue with a YoY growth percentage.",
                    "The output reports operating margin OR EPS for the most recent quarter, with a citation to a 10-Q or 8-K.",
                    "The output names the filing date of the source (formatted YYYY-MM-DD or a full month-day-year), and that date appears to be within roughly the last 4–5 months.",
                    "The output reports at least two of: cash & equivalents, total debt, free cash flow.");

            case "03-valuation" -> List.of(
                    "The output gives the current share price and market cap.",
                    "The output reports at least three of {trailing P/E, forward P/E, P/S, EV/EBITDA, FCF yield} with numeric values.",
                    "The output names at least 2 specific peer companies (by ticker or full name) and gives at least one comparable multiple for each peer.",
                    "The output does NOT itself conclude the stock is cheap, expensive, or fairly valued — it presents numbers without a verdict.");

            case "04-industry-and-peers" -> List.of(
                    "The output gives a one-sentence definition of the industry.",
                    "The output gives a TAM figure or industry growth-rate figure with a citation.",
                    "The output lists at least 4 peer companies with at least one differentiator each.",
                    "The output names at least 2 industry tailwinds AND at least 2 industry headwinds (4 distinct items total).");

            case "05-moat-and-strategy" -> List.of(
                    "The output classifies the moat using at least one of: network effects, scale, intangible assets, switching costs, brand, efficient scale, or 'no clear moat'.",
                    "The output gives at least 2 pieces of quantitative moat evidence (margin trend, retention, share trend, etc.) with citations.",
                    "The output contains at least one direct quoted excerpt (in quotation marks) attributed to an earnings call, shareholder letter, or filing.");

            case "06-catalysts-and-risks" -> List.of(
                    "The output enumerates at least 3 distinct, named near-term catalysts.",
                    "The output enumerates at least 5 distinct risks.",
                    "At least one cited source for catalysts or risks appears to be dated within roughly the last 4 months.",
                    "The risks are specific to this company or industry — not generic ('market volatility', 'macroeconomic conditions') alone.");

            case "07-capital-allocation" -> List.of(
                    "The output reports a current dividend amount (per-share or yield) OR explicitly states the company does not pay a dividend.",
                    "The output gives a buyback figure (dollar amount repurchased OR remaining authorisation) OR explicitly states there is no active buyback programme.",
                    "The output mentions at least one M&A transaction in the last 24 months OR explicitly states there has been none.",
                    "The output references SEC Form 4 or insider-transaction data for the last 6 months in some form.");

            default -> List.of();
        };
    }
}
