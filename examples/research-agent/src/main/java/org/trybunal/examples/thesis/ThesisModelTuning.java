package org.trybunal.examples.thesis;

import java.util.List;
import java.util.Map;
import org.trybunal.api.model.GenerationParams;

/**
 * Per-model tuning of the gathering sub-agent.
 *
 * <p>Lessons carried forward from the {@code agentic-eval} runs in
 * {@code examples/agentic-eval/MODEL-LESSONS.md}: harmony / reasoning
 * channels burn the default token budget before any visible content lands,
 * and the ReAct loop in this module is a worse offender because every
 * tool round-trip costs another few hundred tokens.</p>
 *
 * <p>This class is the single place to bump those budgets. Values are the
 * generation-time {@code maxTokens} per model invocation — the harness
 * issues many such invocations during one agent loop, so the total
 * tokens-per-section-run is a multiple of these numbers.</p>
 */
final class ThesisModelTuning {

    private ThesisModelTuning() {}

    /**
     * Returns generation params tuned for {@code modelName}, falling back
     * to {@code base} when no tweak is needed.
     */
    static GenerationParams paramsFor(String modelName, GenerationParams base) {
        return switch (modelName) {
            // Harmony channel + ReAct = doubly-hungry. 8k headroom.
            case "gpt-oss:20b"           -> withMaxTokens(base, 8192);
            // Reasoning model: burns tokens in <think> before tool calls.
            case "phi4-reasoning:latest" -> withMaxTokens(base, 8192);
            // Gemma routes to a thinking channel before content.
            case "gemma4:26b"            -> withMaxTokens(base, 8192);
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
}
