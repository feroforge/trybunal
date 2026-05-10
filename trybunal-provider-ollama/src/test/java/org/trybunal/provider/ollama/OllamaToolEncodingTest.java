package org.trybunal.provider.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.tool.ToolSpec;

import static org.junit.jupiter.api.Assertions.*;

class OllamaToolEncodingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- encodeTools ---

    @Test
    void encodeTools_producesCorrectShape() throws Exception {
        ToolSpec spec1 = new ToolSpec("web_search", "Search the web",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")));
        ToolSpec spec2 = new ToolSpec("calculator", "Do arithmetic",
                Map.of("type", "object",
                        "properties", Map.of("expression", Map.of("type", "string"))));

        ArrayNode arr = OllamaProvider.encodeTools(List.of(spec1, spec2));

        assertEquals(2, arr.size());

        JsonNode first = arr.get(0);
        assertEquals("function", first.path("type").asText());
        assertEquals("web_search", first.path("function").path("name").asText());
        assertEquals("Search the web", first.path("function").path("description").asText());
        assertTrue(first.path("function").path("parameters").isObject());
        assertEquals("object", first.path("function").path("parameters").path("type").asText());

        JsonNode second = arr.get(1);
        assertEquals("calculator", second.path("function").path("name").asText());
        assertEquals("Do arithmetic", second.path("function").path("description").asText());
    }

    // --- decodeToolCalls ---

    @Test
    void decodeToolCalls_parsesTwo() {
        ObjectNode message = JSON.createObjectNode();
        ArrayNode toolCalls = message.putArray("tool_calls");

        ObjectNode call1 = toolCalls.addObject();
        call1.put("id", "call_abc");
        ObjectNode fn1 = call1.putObject("function");
        fn1.put("name", "web_search");
        fn1.putObject("arguments").put("query", "latest news");

        ObjectNode call2 = toolCalls.addObject();
        call2.put("id", "call_def");
        ObjectNode fn2 = call2.putObject("function");
        fn2.put("name", "calculator");
        fn2.putObject("arguments").put("expression", "2+2");

        List<ToolCall> result = OllamaProvider.decodeToolCalls(message);

        assertEquals(2, result.size());
        assertEquals("call_abc", result.get(0).id());
        assertEquals("web_search", result.get(0).toolName());
        assertEquals("latest news", result.get(0).arguments().get("query"));
        assertEquals("call_def", result.get(1).id());
        assertEquals("calculator", result.get(1).toolName());
    }

    @Test
    void decodeToolCalls_synthesisesIdWhenMissing() {
        ObjectNode message = JSON.createObjectNode();
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode call = toolCalls.addObject();
        ObjectNode fn = call.putObject("function");
        fn.put("name", "some_tool");

        List<ToolCall> result = OllamaProvider.decodeToolCalls(message);

        assertEquals(1, result.size());
        assertTrue(result.get(0).id().startsWith("call_"),
                "synthesised id should start with call_");
    }

    @Test
    void decodeToolCalls_throwsWhenNameMissing() {
        ObjectNode message = JSON.createObjectNode();
        ArrayNode toolCalls = message.putArray("tool_calls");
        ObjectNode call = toolCalls.addObject();
        call.put("id", "call_xyz");
        call.putObject("function"); // no name

        assertThrows(IllegalArgumentException.class,
                () -> OllamaProvider.decodeToolCalls(message));
    }

    @Test
    void decodeToolCalls_returnsEmptyWhenNoToolCalls() {
        ObjectNode message = JSON.createObjectNode();
        message.put("content", "hello");

        List<ToolCall> result = OllamaProvider.decodeToolCalls(message);

        assertTrue(result.isEmpty());
    }
}
