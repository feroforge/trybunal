package org.trybunal.evaluator.llmjudge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelProvider;

import static org.junit.jupiter.api.Assertions.*;

class LlmJudgeEvaluatorTest {

    private static final ModelId JUDGE_ID = new ModelId("stub-judge", "test-model");

    static ModelProvider stubJudge(String reply) {
        return new ModelProvider() {
            @Override public String id() { return "stub-judge"; }
            @Override public boolean supports(ModelId modelId) { return "stub-judge".equals(modelId.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                var meta = new InvocationMetadata(m, Instant.EPOCH, Duration.ZERO,
                        null, null, List.of(), "stop");
                return new InvocationResult(Message.Assistant.of(reply), meta);
            }
        };
    }

    static InvocationResult candidateResult(String text) {
        var meta = new InvocationMetadata(new ModelId("test", "model"), Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), null);
        return new InvocationResult(Message.Assistant.of(text), meta);
    }

    static EvaluationCriteria.LlmRubric rubric(String rubricText) {
        return new EvaluationCriteria.LlmRubric(rubricText, JUDGE_ID);
    }

    // 1. Pass case
    @Test
    void passCase() {
        var evaluator = new LlmJudgeEvaluator(stubJudge("{\"passed\": true, \"score\": 0.9, \"rationale\": \"ok\"}"));
        EvaluationVerdict v = evaluator.evaluate(candidateResult("some output"), rubric("output must be present"));

        assertTrue(v.passed());
        assertEquals(0.9, v.score(), 1e-9);
        assertEquals("ok", v.rationale());
        assertEquals(LlmJudgeEvaluator.ID, v.evaluatorId());
    }

    // 2. Fail case
    @Test
    void failCase() {
        var evaluator = new LlmJudgeEvaluator(stubJudge("{\"passed\": false, \"score\": 0.1, \"rationale\": \"missed\"}"));
        EvaluationVerdict v = evaluator.evaluate(candidateResult("some output"), rubric("must say hello"));

        assertFalse(v.passed());
        assertEquals(0.1, v.score(), 1e-9);
        assertEquals("missed", v.rationale());
    }

    // 3. Verbose judge — JSON wrapped in markdown fences and prose
    @Test
    void verboseJudgeWrappedInFences() {
        String reply = "Sure! Here is my evaluation:\n```json\n{\"passed\": true, \"score\": 0.8, \"rationale\": \"looks good\"}\n```\nThat's my verdict.";
        var evaluator = new LlmJudgeEvaluator(stubJudge(reply));
        EvaluationVerdict v = evaluator.evaluate(candidateResult("output"), rubric("check something"));

        assertTrue(v.passed());
        assertEquals(0.8, v.score(), 1e-9);
        assertEquals("looks good", v.rationale());
    }

    // 4. Malformed judge — plain English, no JSON
    @Test
    void malformedJudgeNoJson() {
        var evaluator = new LlmJudgeEvaluator(stubJudge("I think it looks fine but I cannot decide."));
        EvaluationVerdict v = evaluator.evaluate(candidateResult("output"), rubric("some rubric"));

        assertFalse(v.passed());
        assertTrue(v.rationale().startsWith("Judge returned no JSON block"), "rationale: " + v.rationale());
    }

    // 5. Score clamping — score > 1.0 is clamped to 1.0
    @Test
    void scoreClamping() {
        var evaluator = new LlmJudgeEvaluator(stubJudge("{\"passed\": true, \"score\": 1.7, \"rationale\": \"x\"}"));
        EvaluationVerdict v = evaluator.evaluate(candidateResult("output"), rubric("some rubric"));

        assertTrue(v.passed());
        assertEquals(1.0, v.score(), 1e-9);
    }

    // 6. No provider available — no-arg constructor, no matching provider on classpath
    @Test
    void noProviderAvailable() {
        var evaluator = new LlmJudgeEvaluator();
        // Use a provider id that won't match anything on the test classpath
        var unknownJudge = new ModelId("nonexistent-provider-xyz", "model");
        var criteria = new EvaluationCriteria.LlmRubric("some rubric", unknownJudge);
        EvaluationVerdict v = evaluator.evaluate(candidateResult("output"), criteria);

        assertFalse(v.passed());
        assertTrue(v.rationale().contains("nonexistent-provider-xyz"), "rationale: " + v.rationale());
    }

    // 7. extractJsonBlock unit tests — nested braces inside string literals
    @Test
    void extractJsonBlockHandlesNestedBracesInStrings() {
        // Braces inside the string value must not confuse the parser
        String input = "{\"rationale\": \"score was {0.9} yep\", \"passed\": true, \"score\": 1.0}";
        String result = JudgePromptTemplate.extractJsonBlock(input);
        assertEquals(input, result);
    }

    @Test
    void extractJsonBlockReturnsNullWhenNoBrace() {
        assertNull(JudgePromptTemplate.extractJsonBlock("no braces here"));
    }

    @Test
    void extractJsonBlockReturnsNullForNull() {
        assertNull(JudgePromptTemplate.extractJsonBlock(null));
    }

    @Test
    void extractJsonBlockExtractsFirstBlock() {
        String input = "some prose {\"passed\": false} more text {\"other\": 1}";
        assertEquals("{\"passed\": false}", JudgePromptTemplate.extractJsonBlock(input));
    }

    @Test
    void extractJsonBlockHandlesEscapedQuoteInsideString() {
        // Escaped quote inside string should not flip inString state
        String input = "{\"r\": \"say \\\"hi\\\"\", \"passed\": true}";
        String result = JudgePromptTemplate.extractJsonBlock(input);
        assertEquals(input, result);
    }
}
