package org.trybunal.evaluator.llmjudge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JudgePromptTemplate#extractJsonBlock(String)}.
 *
 * <p>Drives the precedence order documented on the extractor: bare top-level
 * JSON, then think-stripped bare JSON, then fenced JSON, then plain fenced
 * JSON, then the last balanced top-level brace/bracket substring.</p>
 */
class JudgePromptTemplateExtractTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return M.readTree(json);
    }

    // ---- shape (1): bare top-level JSON ----------------------------------

    @Test
    void bareTopLevelObject() throws Exception {
        String input = "{\"passed\":true}";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse(input), parse(out));
    }

    @Test
    void bareTopLevelArray() throws Exception {
        String input = "[{\"passed\":true}]";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse(input), parse(out));
    }

    @Test
    void bareTopLevelObjectWithLeadingAndTrailingWhitespace() throws Exception {
        String input = "\n\n  {\"passed\":true}\n  ";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":true}"), parse(out));
    }

    // ---- shape (2): prelude + JSON ---------------------------------------

    @Test
    void objectWithLeadingProse() throws Exception {
        String input = "Here is my verdict: {\"passed\":false,\"rationale\":\"missed\"}";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":false,\"rationale\":\"missed\"}"), parse(out));
    }

    // ---- shape (3): think-block stripping --------------------------------

    @Test
    void thinkBlockWithBracesThenOuterJsonReturnsOuter() throws Exception {
        String input = "<think>blah {\"x\":1}</think>\n{\"passed\":true}";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":true}"), parse(out));
    }

    @Test
    void thinkBlockNoBracesThenOuterJsonReturnsOuter() throws Exception {
        String input = "<think>blah</think>\n{\"passed\":true}";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":true}"), parse(out));
    }

    // ---- fenced shapes ---------------------------------------------------

    @Test
    void jsonLanguageFence() throws Exception {
        String input = "Sure!\n```json\n{\"passed\":true,\"score\":0.8,\"rationale\":\"ok\"}\n```\nDone.";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":true,\"score\":0.8,\"rationale\":\"ok\"}"), parse(out));
    }

    @Test
    void plainFenceWithJsonObject() throws Exception {
        String input = "Verdict:\n```\n{\"passed\":true}\n```\n";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out);
        assertEquals(parse("{\"passed\":true}"), parse(out));
    }

    // ---- negative cases --------------------------------------------------

    @Test
    void malformedJsonAnywhereReturnsNull() {
        // Looks like JSON, but every candidate position contains a syntax error.
        String input = "Here is the verdict: {passed: true, rationale: missing quotes}";
        assertNull(JudgePromptTemplate.extractJsonBlock(input));
    }

    @Test
    void emptyInputReturnsNull() {
        assertNull(JudgePromptTemplate.extractJsonBlock(""));
    }

    @Test
    void whitespaceOnlyInputReturnsNull() {
        assertNull(JudgePromptTemplate.extractJsonBlock("   \n\t  "));
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(JudgePromptTemplate.extractJsonBlock(null));
    }

    // ---- regression: round-7 checklist failure ---------------------------

    /**
     * Fixture from the {@code llama3.1:8b · 05-moat-and-strategy} round-7
     * run that returned {@code "Judge returned no JSON block. Raw: …"}
     * despite the raw payload being valid JSON. Must parse successfully.
     */
    @Test
    void round7ChecklistFixtureParses() throws Exception {
        String input = "{\"results\": [{\"index\": 1, \"passed\": true, \"rationale\": \"The candidate output begins with a clear thesis statement.\"},"
                + "{\"index\": 2, \"passed\": false, \"rationale\": \"No citations were included.\"},"
                + "{\"index\": 3, \"passed\": true, \"rationale\": \"Discusses moat and strategy.\"}]}";
        String out = JudgePromptTemplate.extractJsonBlock(input);
        assertNotNull(out, "regression: round-7 raw payload should parse");
        JsonNode root = parse(out);
        assertTrue(root.has("results"));
        assertEquals(3, root.get("results").size());
    }
}
