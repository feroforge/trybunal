package org.trybunal.examples;

import java.util.ArrayList;
import java.util.List;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationCriteria.TextMatch.Regex.MatchMode;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;

/**
 * Per-model tuning of the canonical {@link ReadinessSuite}.
 *
 * <p>The base suite is intentionally model-agnostic. This class layers
 * model-specific tweaks on top using only the established value types —
 * no new SPIs, no new framework primitives. Every tweak is one of:</p>
 * <ul>
 *   <li>System-prompt augmentation via {@link PromptSession#withOverride}.</li>
 *   <li>{@link GenerationParams} swap (mostly higher {@code maxTokens}) by
 *       reconstructing the {@link PromptSession} record.</li>
 *   <li>{@link EvaluationCase} reconstruction with a tweaked
 *       {@code userMessage} or {@code criteria}.</li>
 * </ul>
 *
 * <p>Tweaks are derived from the failure modes seen in the first run:
 * see {@code build/agentic-eval/} for the per-model raw outputs that
 * motivated each branch.</p>
 */
final class ModelTuning {

    private ModelTuning() {}

    /** Returns a session augmented for {@code modelName} (or {@code base} unchanged). */
    static PromptSession tuneSession(String modelName, PromptSession base) {
        ModelId modelId = new ModelId("ollama", modelName);
        String suffix = systemPromptSuffix(modelName);
        GenerationParams params = paramsFor(modelName, base.params());

        PromptSession s = base;
        if (!suffix.isEmpty()) {
            s = s.withOverride(modelId, base.basePrompt().strip() + "\n\n" + suffix);
        }
        // Structural equals — sturdier than reference equality if a future
        // arm of paramsFor returns a fresh-but-identical record.
        if (!java.util.Objects.equals(params, base.params())) {
            s = new PromptSession(s.id(), s.name(), s.basePrompt(),
                    s.overrides(), s.seedMessages(), params, s.createdAt());
        }
        return s;
    }

    /** Returns the suite with per-case tweaks applied for {@code modelName}. */
    static List<EvaluationCase> tuneCases(String modelName, List<EvaluationCase> base) {
        var out = new ArrayList<EvaluationCase>(base.size());
        for (EvaluationCase c : base) {
            out.add(tuneCase(modelName, c));
        }
        return List.copyOf(out);
    }

    // ── System-prompt augmentations ──────────────────────────────────────

    private static String systemPromptSuffix(String modelName) {
        return switch (modelName) {
            // Reasoning model leaks </think> tags onto the answer line and
            // exhausts the judge's token budget thinking out loud.
            case "phi4-reasoning:latest" -> """
                    Do NOT output any internal reasoning, scratch work, or
                    <think>...</think> blocks. Produce ONLY the final answer
                    in the exact requested format. If you find yourself
                    starting to reason, stop and emit only the final answer.
                    """;

            // gpt-oss burns the token budget on its analysis channel before
            // writing content. Keep it terse.
            case "gpt-oss:20b" -> """
                    Be concise. When the user requests a specific output format
                    (JSON, numbered list, single token, labeled sections),
                    produce ONLY that format with no commentary, no analysis
                    channel, and no narration. Spend tokens on the answer,
                    not the reasoning.
                    """;

            // llama3.1 emitted {"tool": null, "arguments": {}} even when told
            // explicitly not to call a tool — needs a stronger no-wrapper rule.
            case "llama3.1:8b" -> """
                    When the user explicitly tells you NOT to call a tool,
                    do not emit any JSON tool wrapper at all — not even with
                    null values. Reply in plain text only.
                    """;

            default -> "";
        };
    }

    // ── Generation-param overrides ───────────────────────────────────────

    private static GenerationParams paramsFor(String modelName, GenerationParams base) {
        // Reasoning / harmony-channel models burn many tokens before the
        // answer. Bump max_tokens so structured-output cases don't truncate.
        return switch (modelName) {
            case "gpt-oss:20b"           -> withMaxTokens(base, 4096);
            case "phi4-reasoning:latest" -> withMaxTokens(base, 4096);
            // gemma4 routes its output to a separate "thinking" channel before
            // emitting content. With the default 1024-token budget, thinking
            // exhausted the budget and content stayed empty. We KEEP thinking
            // enabled — disabling it would neuter the test on the 6 cases that
            // genuinely benefit from chain-of-thought (B3, C1, C2, C3, D1, D2).
            // Direct probe showed gemma uses ~2644 tokens total (thinking +
            // content) on a B-class prompt; 8192 leaves comfortable headroom.
            case "gemma4:26b"            -> withMaxTokens(base, 8192);
            default                      -> base;
        };
    }

    private static GenerationParams withMaxTokens(GenerationParams base, int maxTokens) {
        return new GenerationParams(base.temperature(), maxTokens,
                base.topP(), base.seed(), base.providerExtras());
    }

    // ── Per-case nudges ──────────────────────────────────────────────────

    private static EvaluationCase tuneCase(String modelName, EvaluationCase c) {
        String key = modelName + "::" + c.name();
        return switch (key) {

            // phi4-reasoning leaked </think>KNIGHT_IS_A onto a single line,
            // missing the strict line-anchored regex. Two-pronged fix: add
            // an explicit format reminder AND relax the regex to accept the
            // token anywhere (\b...\b), since strict anchoring is hostile to
            // reasoning models.
            case "phi4-reasoning:latest::C2-knights-and-knaves" ->
                    new EvaluationCase(
                            c.name(),
                            augmentMessage(c.userMessage(), """
                                    Your reply must contain the literal token
                                    KNIGHT_IS_A or KNIGHT_IS_B with no </think>
                                    tag, no period, and nothing else attached.
                                    """),
                            new EvaluationCriteria.TextMatch.Regex(
                                    "(?i)\\bKNIGHT_IS_A\\b",
                                    MatchMode.FIND));

            // Same root cause: phi4-reasoning emits </think>1. Design… on the
            // first numbered-list line, so the line-anchored regex misses.
            // Relax to "1. through 5. all appear in order anywhere".
            case "phi4-reasoning:latest::B1-numbered-plan" ->
                    new EvaluationCase(
                            c.name(),
                            c.userMessage(),
                            new EvaluationCriteria.TextMatch.Regex(
                                    "(?s)\\b1[.)].*?\\b2[.)].*?\\b3[.)].*?\\b4[.)].*?\\b5[.)]",
                                    MatchMode.FIND));

            // gpt-oss returned EMPTY content for A1 across R1–R5. The single-tool
            // spec format triggers a harmony tool-call channel that doesn't
            // surface as message.content. The multi-tool spec (A2/A4 format)
            // works fine. Use the multi-tool spec here, naturally constraining
            // the tool selection to what the user asks for ("weather in Tokyo"
            // → only get_weather is plausible).
            case "gpt-oss:20b::A1-tool-single-arg" ->
                    new EvaluationCase(
                            c.name(),
                            """
                            You have access to these tools:
                            - get_weather(city: string)
                            - web_search(query: string, max_results: integer)
                            - send_email(to: string, subject: string, body: string)
                            Reply with ONLY a single JSON object of the shape:
                            {"tool": "<name>", "arguments": { ... }}
                            No other text. No code fences.
                            User: What's the weather in Tokyo right now?
                            """,
                            c.criteria());

            // Demonstrates per-case paramsOverride: only the long D-cases
            // need 8192 tokens for gpt-oss; the short cases finish in <100.
            // Cranking session-level maxTokens slows the cheap cases for no
            // benefit — per-case override gives each case the right budget.
            case "gpt-oss:20b::D1-tech-facts-args-speculation",
                 "gpt-oss:20b::D2-energy-facts-args-speculation" -> {
                var bumped = new org.trybunal.api.model.GenerationParams(
                        0.2, 8192, null, 42L, java.util.Map.of());
                yield new EvaluationCase(c.name(), c.userMessage(), c.criteria(), bumped);
            }

            // B3 had a per-model nudge prescribing a "DAO layer between
            // schema and handlers" — that revealed the rubric's expected
            // architecture and reduced the test from "does the model
            // understand dependency-aware layering?" to "can it follow an
            // architectural instruction?". Removed. The canonical prompt
            // already says "each step must be executable given only the
            // steps before it"; if a model skips the DAO layer, that's a
            // real readiness failure we want to surface.

            // The earlier "strict citation rule" nudge for D1/D2 ("every fact
            // you reference by number must appear in FACTS") is now an
            // explicit check (#4) in the LlmRubricChecklist for those cases.
            // Telling the model the check via a nudge would leak the rubric's
            // structure, so it was removed. Models that fail check #4 should
            // legitimately fail check #4.

            // A3 restraint: models that emit a tool wrapper despite being
            // told not to. Earlier nudge here read "Your entire reply must
            // be the single character '4'" — that gave away the arithmetic
            // answer and reduced the case to instruction-copying. Reworded
            // to be format-only: it forbids the JSON wrapper without
            // mentioning what 2+2 equals. The model still has to compute.
            case "llama3.1:8b::A3-tool-negative-restraint",
                 "phi4-reasoning:latest::A3-tool-negative-restraint",
                 "gpt-oss:20b::A3-tool-negative-restraint" ->
                    augment(c, """
                            Output your answer as plain prose only. Do not
                            emit any JSON, no curly braces, no quotation
                            marks around the answer, no keys named "tool"
                            or "arguments", and no field structure of any
                            kind. Compute the result yourself and write it
                            in natural language.
                            """);

            default -> c;
        };
    }

    private static EvaluationCase augment(EvaluationCase c, String suffix) {
        return new EvaluationCase(
                c.name(),
                augmentMessage(c.userMessage(), suffix),
                c.criteria());
    }

    private static String augmentMessage(String original, String suffix) {
        return original.stripTrailing()
                + "\n\nADDITIONAL CONSTRAINT:\n" + suffix.strip() + "\n";
    }
}
