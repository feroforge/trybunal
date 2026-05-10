package org.trybunal.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationCriteria.TextMatch.Regex.MatchMode;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;

/**
 * The 12-case agentic-readiness suite.
 *
 * <p>Cases are grouped by dimension prefix:</p>
 * <ul>
 *   <li><b>A1–A4</b> — tool use (emit a parseable JSON tool call, or
 *       correctly decline to call one).</li>
 *   <li><b>B1–B3</b> — task decomposition (numbered plans, JSON step
 *       arrays, dependency-aware orderings).</li>
 *   <li><b>C1–C3</b> — reasoning (multi-step arithmetic, knights &amp;
 *       knaves logic, constraint-satisfaction scheduling).</li>
 *   <li><b>D1–D2</b> — facts → arguments → speculation on a domain
 *       passage.</li>
 * </ul>
 *
 * <p>Stateless. Static methods only.</p>
 */
final class ReadinessSuite {

    private ReadinessSuite() {}

    /** Low-temperature session — these are evals, not creative writing. */
    static PromptSession buildSession() {
        var params = new GenerationParams(0.2, 1024, null, 42L, Map.of());
        return new PromptSession(
                null,
                "agentic-readiness",
                """
                You are an AI agent under evaluation. Follow instructions
                exactly. When asked for a specific output format (JSON,
                numbered list, single token), produce ONLY that — no
                preamble, no apology, no markdown fences unless requested.
                """,
                Map.of(),
                List.of(),
                params,
                null);
    }

    /** Builds the 12-case suite. The {@code judge} id is used by rubric cases. */
    static List<EvaluationCase> buildSuite(ModelId judge) {
        var all = new ArrayList<EvaluationCase>();
        all.addAll(toolUseCases(judge));
        all.addAll(decompositionCases(judge));
        all.addAll(reasoningCases(judge));
        all.addAll(factsArgsSpeculationCases(judge));
        return List.copyOf(all);
    }

    // ── A. Tool use — emit a parseable tool call ─────────────────────────

    private static List<EvaluationCase> toolUseCases(ModelId judge) {
        String toolSpec = """
                You have access to ONE tool:
                {
                  "name": "get_weather",
                  "description": "Returns current weather for a city.",
                  "parameters": { "city": "string (required)" }
                }
                When you decide to call it, reply with ONLY a JSON object
                of the exact shape:
                {"tool": "get_weather", "arguments": {"city": "<value>"}}
                No other text. No code fences.
                """;

        String multiToolSpec = """
                You have access to these tools:
                - get_weather(city: string)
                - web_search(query: string, max_results: integer)
                - send_email(to: string, subject: string, body: string)
                Reply with ONLY a single JSON object of the shape:
                {"tool": "<name>", "arguments": { ... }}
                No other text. No code fences.
                """;

        return List.of(
                new EvaluationCase(
                        "A1-tool-single-arg",
                        toolSpec + "\nUser: What's the weather in Tokyo right now?",
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?is)\"tool\"\\s*:\\s*\"get_weather\".*?\"city\"\\s*:\\s*\"Tokyo\"",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "A2-tool-multi-arg",
                        multiToolSpec + "\nUser: Search the web for \"hexagonal architecture java\" and give me the top 3 results.",
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?is)\"tool\"\\s*:\\s*\"web_search\".*?\"query\"\\s*:\\s*\"[^\"]*hexagonal[^\"]*\".*?\"max_results\"\\s*:\\s*3",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "A3-tool-negative-restraint",
                        multiToolSpec
                                + "\nUser: What is 2 + 2? Answer in plain text. Do NOT call any tool for this — answer directly with just the number.",
                        new EvaluationCriteria.LlmRubric(
                                """
                                The candidate is correct iff ALL hold:
                                  (a) it contains the digit 4 as the answer,
                                  (b) it does NOT contain a JSON object with a
                                      key named "tool" (i.e. it did not invoke
                                      any tool — it answered directly in prose).
                                Mark passed=true only if both (a) and (b) hold.
                                """,
                                judge)),

                new EvaluationCase(
                        "A4-tool-selection",
                        multiToolSpec + "\nUser: Email alice@example.com a one-line note saying \"meeting moved to 3pm\". Subject should be \"Meeting time change\".",
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?is)\"tool\"\\s*:\\s*\"send_email\".*?\"to\"\\s*:\\s*\"alice@example\\.com\".*?\"subject\"\\s*:\\s*\"Meeting time change\"",
                                MatchMode.FIND))
        );
    }

    // ── B. Task decomposition ─────────────────────────────────────────────

    private static List<EvaluationCase> decompositionCases(ModelId judge) {
        return List.of(
                new EvaluationCase(
                        "B1-numbered-plan",
                        """
                        Goal: ship a "forgot password" feature in a web app
                        with email-based reset. Output a numbered plan with
                        AT LEAST 5 concrete engineering steps, in execution
                        order. Use the format "1. ...", "2. ...", etc.
                        Plain text only — no preamble.
                        """,
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?s)(?m)^\\s*1[.)].*?^\\s*2[.)].*?^\\s*3[.)].*?^\\s*4[.)].*?^\\s*5[.)]",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "B2-json-steps-array",
                        """
                        Decompose "plan a small in-office birthday party for a
                        coworker" into discrete steps. Reply with ONLY a JSON
                        object of the shape:
                        {"steps": ["step 1...", "step 2...", ...]}
                        At least 4 steps. No prose, no code fences.
                        """,
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?is)\"steps\"\\s*:\\s*\\[\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "B3-dependency-order",
                        """
                        You are building a CRUD REST API for a "tasks"
                        resource backed by Postgres. Produce a numbered plan
                        of 6–10 steps. Order matters: each step must be
                        executable given only the steps before it. Plain
                        text. No preamble.
                        """,
                        new EvaluationCriteria.LlmRubric(
                                """
                                The candidate is a numbered plan (1., 2., …) of
                                6–10 steps for building a CRUD REST API on
                                Postgres. Mark passed=true iff:
                                  - schema/migration design comes BEFORE writing
                                    DAO/repository code,
                                  - DAO/repository or model layer comes BEFORE
                                    HTTP handlers/controllers,
                                  - HTTP handlers come BEFORE integration tests
                                    or deployment,
                                  - the steps are concrete (not "design the
                                    system") and free of duplicates.
                                If any of those orderings is violated, fail.
                                """,
                                judge))
        );
    }

    // ── C. Reasoning ──────────────────────────────────────────────────────

    private static List<EvaluationCase> reasoningCases(ModelId judge) {
        return List.of(
                new EvaluationCase(
                        "C1-multi-step-arithmetic",
                        """
                        A bookstore had 240 books. On Monday it sold 1/4 of
                        them. On Tuesday it sold 30% of the REMAINING books.
                        On Wednesday a shipment arrived doubling whatever was
                        left. How many books are now in the store? Show your
                        reasoning briefly, then on a final line write exactly:
                        ANSWER: <number>
                        """,
                        // 240 * 3/4 = 180; 180 * 0.7 = 126; 126 * 2 = 252.
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?im)^\\s*ANSWER:\\s*252\\b",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "C2-knights-and-knaves",
                        """
                        On an island, knights always tell the truth and
                        knaves always lie. You meet two people, A and B.
                          A says: "B is a knave."
                          B says: "A and I are the same kind."
                        Who is the knight? Reply with EXACTLY one of these
                        tokens on the final line: KNIGHT_IS_A or KNIGHT_IS_B.
                        Brief reasoning is fine before the final line.
                        """,
                        new EvaluationCriteria.TextMatch.Regex(
                                "(?im)^\\s*KNIGHT_IS_A\\s*$",
                                MatchMode.FIND)),

                new EvaluationCase(
                        "C3-scheduling-constraints",
                        """
                        Schedule three 30-minute meetings — Alpha, Beta,
                        Gamma — into the slots 9:00, 9:30, 10:00, 10:30, 11:00.
                        Constraints:
                          - Alpha must be earlier than Beta.
                          - Gamma cannot be at 9:00 or 11:00.
                          - Beta cannot be immediately after Alpha (no
                            back-to-back Alpha→Beta).
                        Pick one valid schedule. Reply in EXACTLY this format
                        on three lines and nothing else:
                          Alpha: <time>
                          Beta: <time>
                          Gamma: <time>
                        """,
                        new EvaluationCriteria.LlmRubricChecklist(
                                List.of(
                                        "The candidate output contains three lines of the form 'Alpha: HH:MM', 'Beta: HH:MM', 'Gamma: HH:MM', and all three times are drawn from the set {9:00, 9:30, 10:00, 10:30, 11:00}.",
                                        "The three assigned times are all distinct.",
                                        "Alpha's time is strictly earlier than Beta's time.",
                                        "Gamma's time is not 9:00 and not 11:00.",
                                        "Beta is NOT in the slot immediately after Alpha (i.e. there is at least one 30-minute gap between Alpha's slot and Beta's slot)."),
                                judge))
        );
    }

    // ── D. Facts → Arguments → Speculation ────────────────────────────────

    private static List<EvaluationCase> factsArgsSpeculationCases(ModelId judge) {
        String passageTech = """
                In Q3, a mid-size SaaS company "Lumen" reported these numbers:
                ARR grew 18% YoY to $42M; net revenue retention dropped from
                118% to 104%; gross margin held at 78%; sales-and-marketing
                spend rose 31%; cash runway is now 14 months at current burn;
                two of the top five customers (representing 22% of ARR
                combined) downgraded their plans. Headcount grew 12% in the
                quarter, mostly in sales.
                """;

        String passageEnergy = """
                A regional electricity grid (population ~9M) had this year:
                peak summer demand up 7%, winter peak up 11%; rooftop solar
                now meets 14% of midday demand (up from 6% two years ago);
                battery storage capacity tripled to 1.2 GWh; the grid
                operator retired one 600 MW gas peaker and delayed another
                retirement by two years; transmission upgrades are 18 months
                behind schedule due to permitting delays. Wholesale prices
                have a new bimodal pattern — very low midday, sharp evening
                spikes.
                """;

        List<String> tripleStepChecks = List.of(
                "The candidate output contains three labeled sections in order: 'FACTS', 'ARGUMENTS', 'SPECULATION', each on its own line as a header (no markdown).",
                "The FACTS section is a list of at least 4 distinct facts that are all directly supported by the passage — no fabricated numbers, no hallucinated entities.",
                "The ARGUMENTS section contains at least 2 arguments, and each argument explicitly references one or more facts from the FACTS section above (a fact-grounded chain of reasoning, not a vague platitude).",
                "Every fact referenced by number in ARGUMENTS (e.g. 'Fact 5') corresponds to an actual numbered item in the FACTS section — no citation may exceed the number of facts listed.",
                "The SPECULATION section contains exactly one forward-looking outcome, framed as a conditional prediction (e.g. 'If X continues, then Y…') and plausibly tied to the arguments — not a generic truism."
        );

        String tripleStepInstructions = """
                Read the passage. Then output THREE labeled sections, in
                this exact order and using these exact headers (each on its
                own line, uppercase, no markdown):
                  FACTS
                  ARGUMENTS
                  SPECULATION
                Under FACTS, list at least 4 facts pulled DIRECTLY from the
                passage — no outside knowledge, no invented numbers.
                Under ARGUMENTS, write at least 2 arguments. Each argument
                must explicitly cite which fact(s) it depends on.
                Under SPECULATION, write exactly ONE forward-looking
                prediction in the form "If <condition>, then <consequence>",
                grounded in the arguments above.
                """;

        return List.of(
                new EvaluationCase(
                        "D1-tech-facts-args-speculation",
                        tripleStepInstructions + "\n\nPASSAGE:\n" + passageTech,
                        new EvaluationCriteria.LlmRubricChecklist(tripleStepChecks, judge)),

                new EvaluationCase(
                        "D2-energy-facts-args-speculation",
                        tripleStepInstructions + "\n\nPASSAGE:\n" + passageEnergy,
                        new EvaluationCriteria.LlmRubricChecklist(tripleStepChecks, judge))
        );
    }
}
