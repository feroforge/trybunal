package org.trybunal.provider.ollama;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;

/**
 * Drives {@link OllamaProvider#decodeResponse} with synthetic responses whose
 * tool-call signal lives in {@code message.content} rather than the structured
 * {@code message.tool_calls} array. Verifies that the text fallback promotes
 * the calls into both the reply and the metadata, strips the parsed prefix
 * from the content, and stamps {@code tool_call_origin} into the extras.
 */
class OllamaToolCallTextFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModelId LLAMA = new ModelId("ollama", "llama3.1:8b");
    private static final ModelId MISTRAL = new ModelId("ollama", "mistral-small:24b");
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void llamaPythonTagInContentBecomesStructuredToolCall() throws Exception {
        var root = JSON.readTree("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "<|python_tag|>get_weather(city=\\"Tokyo\\")",
                    "tool_calls": []
                  },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, LLAMA, T0);

        assertEquals(1, result.reply().toolCalls().size());
        assertEquals("get_weather", result.reply().toolCalls().get(0).toolName());
        assertEquals("Tokyo", result.reply().toolCalls().get(0).arguments().get("city"));
        assertEquals(1, result.metadata().toolCalls().size());
        assertEquals("get_weather", result.metadata().toolCalls().get(0).toolName());
        assertEquals("", result.reply().content(),
                "parsed marker + call should be stripped from content");
        assertEquals("text:python_tag",
                result.metadata().providerExtras().get("tool_call_origin"));
    }

    @Test
    void mistralToolCallsPlainInContentBecomesStructuredCall() throws Exception {
        var root = JSON.readTree("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "[TOOL_CALLS]web_search(\\"apple\\", limit=3)",
                    "tool_calls": []
                  },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, MISTRAL, T0);

        assertEquals(1, result.metadata().toolCalls().size());
        var call = result.metadata().toolCalls().get(0);
        assertEquals("web_search", call.toolName());
        assertEquals("apple", call.arguments().get("query"));
        assertEquals(3L, call.arguments().get("limit"));
        assertEquals("text:tool_calls",
                result.metadata().providerExtras().get("tool_call_origin"));
    }

    @Test
    void mistralToolCallsJsonArrayInContentBecomesStructuredCall() throws Exception {
        var root = JSON.readTree("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "[TOOL_CALLS][{\\"name\\":\\"x\\",\\"arguments\\":{\\"a\\":1}}]",
                    "tool_calls": []
                  },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, MISTRAL, T0);

        assertEquals(1, result.metadata().toolCalls().size());
        assertEquals("x", result.metadata().toolCalls().get(0).toolName());
        assertEquals(1,
                ((Number) result.metadata().toolCalls().get(0).arguments().get("a")).intValue());
        assertEquals("text:tool_calls_json",
                result.metadata().providerExtras().get("tool_call_origin"));
    }

    @Test
    void structuredToolCallsTakePrecedenceOverTextMarker() throws Exception {
        // Both channels populated: the structured array wins and the parser
        // is not invoked. This guards against double-counting if a future
        // Ollama build starts emitting both surfaces.
        var root = JSON.readTree("""
                {
                  "message": {
                    "role": "assistant",
                    "content": "<|python_tag|>get_weather(city=\\"Tokyo\\")",
                    "tool_calls": [
                      {"function": {"name": "structured_tool", "arguments": {"k": "v"}}}
                    ]
                  },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, LLAMA, T0);

        assertEquals(1, result.metadata().toolCalls().size());
        assertEquals("structured_tool", result.metadata().toolCalls().get(0).toolName());
        assertEquals("structured",
                result.metadata().providerExtras().get("tool_call_origin"));
        assertEquals("<|python_tag|>get_weather(city=\"Tokyo\")", result.reply().content(),
                "content untouched when structured path wins");
    }

    @Test
    void noToolCallsLeavesOriginAbsent() throws Exception {
        var root = JSON.readTree("""
                {
                  "message": { "role": "assistant", "content": "just prose" },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, LLAMA, T0);

        assertTrue(result.metadata().toolCalls().isEmpty());
        assertFalse(result.metadata().providerExtras().containsKey("tool_call_origin"),
                "tool_call_origin must be absent when no calls were produced");
    }

    @Test
    void blankContentSkipsFallbackParser() throws Exception {
        // Empty content means there's nothing to parse; the parser should
        // not even be invoked, so origin stays absent.
        var root = JSON.readTree("""
                {
                  "message": { "role": "assistant", "content": "" },
                  "done_reason": "stop"
                }
                """);

        InvocationResult result = OllamaProvider.decodeResponse(root, LLAMA, T0);

        assertTrue(result.metadata().toolCalls().isEmpty());
        assertFalse(result.metadata().providerExtras().containsKey("tool_call_origin"));
    }
}
