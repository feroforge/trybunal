package org.trybunal.provider.ollama;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.ToolCall;
import org.trybunal.provider.ollama.ToolCallTextParser.ParseResult;

/**
 * Unit tests for {@link ToolCallTextParser}. Covers the two model-native
 * text channels (Llama {@code <|python_tag|>} and Mistral {@code [TOOL_CALLS]})
 * plus the failure modes the parser must absorb without throwing.
 */
class ToolCallTextParserTest {

    @Test
    void parsesLlamaPythonTagSingleCall() {
        ParseResult r = ToolCallTextParser.parse(
                "<|python_tag|>get_weather(city=\"Tokyo\")");

        assertEquals(1, r.calls().size());
        ToolCall call = r.calls().get(0);
        assertEquals("get_weather", call.toolName());
        assertEquals("Tokyo", call.arguments().get("city"));
        assertEquals(1, call.arguments().size());
        assertEquals("", r.remainingContent());
    }

    @Test
    void parsesMistralPlainCallWithPositionalAndNamedArgs() {
        ParseResult r = ToolCallTextParser.parse(
                "[TOOL_CALLS]web_search(\"apple\", limit=3)");

        assertEquals(1, r.calls().size());
        ToolCall call = r.calls().get(0);
        assertEquals("web_search", call.toolName());
        assertEquals("apple", call.arguments().get("query"));
        assertEquals(3L, call.arguments().get("limit"));
        assertEquals("", r.remainingContent());
    }

    @Test
    void parsesMistralJsonArrayCall() {
        ParseResult r = ToolCallTextParser.parse(
                "[TOOL_CALLS][{\"name\":\"x\",\"arguments\":{\"a\":1}}]");

        assertEquals(1, r.calls().size());
        ToolCall call = r.calls().get(0);
        assertEquals("x", call.toolName());
        assertEquals(1, ((Number) call.arguments().get("a")).intValue());
        assertEquals("", r.remainingContent());
    }

    @Test
    void parsesTwoCallsSeparatedByNewline() {
        ParseResult r = ToolCallTextParser.parse(
                "<|python_tag|>foo(a=1)\nbar(b=\"two\")");

        assertEquals(2, r.calls().size());
        assertEquals("foo", r.calls().get(0).toolName());
        assertEquals(1L, r.calls().get(0).arguments().get("a"));
        assertEquals("bar", r.calls().get(1).toolName());
        assertEquals("two", r.calls().get(1).arguments().get("b"));
        assertEquals("", r.remainingContent());
    }

    @Test
    void markerPresentButBodyMalformedReturnsEmptyCallsAndOriginalContent() {
        String raw = "<|python_tag|>broken((((no closing parens here";

        ParseResult r = ToolCallTextParser.parse(raw);

        assertTrue(r.calls().isEmpty(),
                "malformed body should yield zero calls, got: " + r.calls());
        assertEquals(raw, r.remainingContent(),
                "original content should be preserved when parse fails");
    }

    @Test
    void noMarkerReturnsEmptyCallsAndOriginalContent() {
        String raw = "I think the answer is 42.";

        ParseResult r = ToolCallTextParser.parse(raw);

        assertTrue(r.calls().isEmpty());
        assertEquals(raw, r.remainingContent());
    }

    @Test
    void parseResultCallsListIsDefensivelyCopied() {
        List<ToolCall> source = new ArrayList<>();
        source.add(new ToolCall("call_x", "foo", java.util.Map.of()));
        ParseResult r = new ParseResult(source, "");
        source.clear();

        assertEquals(1, r.calls().size(),
                "mutating the source list must not affect the ParseResult");
    }

    @Test
    void nullContentToleratedAsEmpty() {
        ParseResult r = ToolCallTextParser.parse(null);
        assertTrue(r.calls().isEmpty());
        assertEquals("", r.remainingContent());
    }

    @Test
    void markerAfterLeadingWhitespaceStillRecognised() {
        ParseResult r = ToolCallTextParser.parse(
                "   \n<|python_tag|>get_weather(city=\"Tokyo\")");

        assertEquals(1, r.calls().size());
        assertEquals("get_weather", r.calls().get(0).toolName());
    }
}
