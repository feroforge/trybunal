package org.trybunal.evaluator.assertions;

import org.junit.jupiter.api.Test;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EqualsEvaluatorTest {

    private final EqualsEvaluator evaluator = new EqualsEvaluator();

    static InvocationResult reply(String text) {
        var modelId = new ModelId("test", "x");
        var meta = new InvocationMetadata(
                modelId, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), null);
        return new InvocationResult(Message.Assistant.of(text), meta);
    }

    @Test
    void id() {
        assertEquals("equals", evaluator.id());
    }

    @Test
    void supportsEquals() {
        assertTrue(evaluator.supports(new EvaluationCriteria.TextMatch.Equals("x", false)));
    }

    @Test
    void doesNotSupportLlmRubric() {
        assertFalse(evaluator.supports(new EvaluationCriteria.LlmRubric("rubric", new ModelId("a", "b"))));
    }

    @Test
    void evaluateWithUnsupportedCriteriaThrows() {
        var rubric = new EvaluationCriteria.LlmRubric("rubric", new ModelId("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(reply("hi"), rubric));
    }

    @Test
    void passCase() {
        var criteria = new EvaluationCriteria.TextMatch.Equals("hello", false);
        EvaluationVerdict v = evaluator.evaluate(reply("hello"), criteria);
        assertTrue(v.passed());
        assertEquals(1.0, v.score());
        assertFalse(v.rationale().isEmpty());
    }

    @Test
    void failCase() {
        var criteria = new EvaluationCriteria.TextMatch.Equals("hello", false);
        EvaluationVerdict v = evaluator.evaluate(reply("world"), criteria);
        assertFalse(v.passed());
        assertEquals(0.0, v.score());
        assertTrue(v.rationale().contains("hello"));
        assertTrue(v.rationale().contains("world"));
    }

    @Test
    void trimTrueIgnoresWhitespace() {
        var criteria = new EvaluationCriteria.TextMatch.Equals("hello", true);
        EvaluationVerdict v = evaluator.evaluate(reply("  hello  "), criteria);
        assertTrue(v.passed());
    }

    @Test
    void trimFalseRejectsWhitespace() {
        var criteria = new EvaluationCriteria.TextMatch.Equals("hello", false);
        EvaluationVerdict v = evaluator.evaluate(reply("  hello  "), criteria);
        assertFalse(v.passed());
    }
}
