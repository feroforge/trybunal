package org.trybunal.provider.ollama;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.ModelId;

/**
 * Unit tests for {@link OllamaProvider#decodeResponse} — the JSON-shape
 * concerns split out of the HTTP-bound {@link OllamaProvider#invoke}.
 *
 * <p>Live transport is covered by {@link OllamaProviderIntegrationTest} and
 * skipped without {@code OLLAMA_URL} set.</p>
 */
class OllamaProviderDecodeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModelId MODEL = new ModelId("ollama", "gemma:7b");
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void capturesThinkingFieldIntoProviderExtras() throws Exception {
        // Shape mirrors what gemma + thinking-capable models return:
        // reasoning routes to message.thinking, answer to message.content.
        var root = JSON.readTree("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "Final answer.",
                    "thinking": "Step 1: ... Step 2: ..."
                  },
                  "done_reason": "stop",
                  "prompt_eval_count": 12,
                  "eval_count": 34,
                  "total_duration": 1500000000
                }
                """);

        var result = OllamaProvider.decodeResponse(root, MODEL, T0);

        assertEquals("Final answer.", result.reply().content());
        assertEquals("Step 1: ... Step 2: ...",
                result.metadata().providerExtras().get("thinking"));
        assertEquals("stop", result.metadata().finishReason());
        assertEquals(12, result.metadata().promptTokens());
        assertEquals(34, result.metadata().completionTokens());
    }

    @Test
    void omitsThinkingKeyWhenAbsent() throws Exception {
        // Non-reasoning model: no thinking field at all.
        var root = JSON.readTree("""
                {
                  "message": { "role": "assistant", "content": "hi" },
                  "done_reason": "stop"
                }
                """);

        var result = OllamaProvider.decodeResponse(root, MODEL, T0);

        assertEquals("hi", result.reply().content());
        assertFalse(result.metadata().providerExtras().containsKey("thinking"));
        assertTrue(result.metadata().providerExtras().isEmpty());
    }

    @Test
    void omitsThinkingKeyWhenBlank() throws Exception {
        // Some daemons send the field but empty when the model didn't think.
        // Treat blank as absent — no point lugging "" through reports.
        var root = JSON.readTree("""
                {
                  "message": { "role": "assistant", "content": "hi", "thinking": "   " },
                  "done_reason": "stop"
                }
                """);

        var result = OllamaProvider.decodeResponse(root, MODEL, T0);

        assertFalse(result.metadata().providerExtras().containsKey("thinking"));
    }

    @Test
    void emptyContentStillProducesAssistantMessage() throws Exception {
        // The original bug: reasoning consumed the whole token budget inside
        // <think>, content came back empty. We still want a well-formed
        // InvocationResult so the orchestrator can record the case as failing
        // gracefully rather than NPE'ing.
        var root = JSON.readTree("""
                {
                  "message": { "role": "assistant", "content": "", "thinking": "ran out of budget" },
                  "done_reason": "length"
                }
                """);

        var result = OllamaProvider.decodeResponse(root, MODEL, T0);

        assertEquals("", result.reply().content());
        assertEquals("length", result.metadata().finishReason());
        assertEquals("ran out of budget",
                result.metadata().providerExtras().get("thinking"));
    }
}
