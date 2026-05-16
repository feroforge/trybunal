package org.trybunal.examples.thesis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.model.GenerationParams;

/**
 * Per-model tuning of the gathering sub-agent.
 *
 * <p>Lessons carried forward from the {@code agentic-eval} runs in
 * {@code examples/agentic-eval/MODEL-LESSONS.md} and the per-tool
 * smoke test in {@code examples/tool-smoke}: harmony / reasoning
 * channels burn the default token budget before any visible content lands,
 * and the ReAct loop in this module is a worse offender because every
 * tool round-trip costs another few hundred tokens.</p>
 *
 * <p>This class is the single place to apply those tweaks. The two knobs
 * are {@code maxTokens} (per-call budget) and {@code providerExtras["think"]}
 * (sent as Ollama's top-level {@code think} flag — when {@code false} the
 * model skips the thinking channel entirely).</p>
 */
public final class ThesisModelTuning {

    private ThesisModelTuning() {}

    /**
     * Returns generation params tuned for {@code modelName}, falling back
     * to {@code base} when no tweak is needed.
     */
    public static GenerationParams paramsFor(String modelName, GenerationParams base) {
        return switch (modelName) {
            // Harmony channel + ReAct = doubly-hungry. 8k headroom.
            case "gpt-oss:20b"           -> withMaxTokens(base, 8192);
            // Reasoning model: burns tokens in <think> before tool calls.
            case "phi4-reasoning:latest" -> withMaxTokens(base, 8192);
            // gemma4:26b: leave thinking ENABLED (think:true is the default)
            // and bump tokens. We learned the hard way that setting
            // think:false on gemma+Ollama, combined with two or more
            // assistant→tool exchanges, makes Ollama return an empty
            // placeholder response ({"model":"","done":false,...}) and stall
            // the ReAct loop. Thinking is fine here — gemma uses ~600 tokens
            // per turn for reasoning before producing the tool call. Bigger
            // context window because each web_fetch tool result can carry
            // ~20k chars of HTML into the conversation.
            case "gemma4:26b"            -> withNumCtx(
                    withMaxTokens(base, 16384), 32768);
            // Mid-size models: bump from 4k default to 6k for safety on
            // the longer sections (industry, catalysts).
            case "mistral-small:24b",
                 "phi4:14b"              -> withMaxTokens(base, 6144);
            default                      -> base;
        };
    }

    private static GenerationParams withMaxTokens(GenerationParams base, int maxTokens) {
        return new GenerationParams(base.temperature(), maxTokens,
                base.topP(), base.seed(),
                base.providerExtras() == null ? Map.of() : base.providerExtras(),
                base.tools()         == null ? List.of() : base.tools());
    }

    private static GenerationParams withThinking(GenerationParams base, boolean think) {
        Map<String, Object> extras = new LinkedHashMap<>(
                base.providerExtras() == null ? Map.of() : base.providerExtras());
        extras.put("think", think);
        return new GenerationParams(base.temperature(), base.maxTokens(),
                base.topP(), base.seed(), extras,
                base.tools() == null ? List.of() : base.tools());
    }

    /**
     * Sets Ollama's {@code num_ctx} — the prompt-context window the model
     * loads with. Ollama defaults to 4 096 tokens regardless of the model's
     * declared maximum, which silently truncates large web_fetch / web_browser
     * results. Bigger windows raise VRAM cost; tune per model.
     */
    private static GenerationParams withNumCtx(GenerationParams base, int numCtx) {
        Map<String, Object> extras = new LinkedHashMap<>(
                base.providerExtras() == null ? Map.of() : base.providerExtras());
        extras.put("num_ctx", numCtx);
        return new GenerationParams(base.temperature(), base.maxTokens(),
                base.topP(), base.seed(), extras,
                base.tools() == null ? List.of() : base.tools());
    }
}
