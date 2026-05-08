package org.trybunal.evaluator.assertions;

import org.junit.jupiter.api.Test;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationCriteria.TextMatch.Regex;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegexEvaluatorTest {

    private final RegexEvaluator evaluator = new RegexEvaluator();

    static InvocationResult reply(String text) {
        var modelId = new ModelId("test", "x");
        var meta = new InvocationMetadata(
                modelId, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), null);
        return new InvocationResult(Message.Assistant.of(text), meta);
    }

    @Test
    void id() {
        assertEquals("regex", evaluator.id());
    }

    @Test
    void supportsRegex() {
        assertTrue(evaluator.supports(new Regex("\\d+", Regex.MatchMode.FIND)));
    }

    @Test
    void doesNotSupportContains() {
        assertFalse(evaluator.supports(new EvaluationCriteria.TextMatch.Contains("x", true)));
    }

    @Test
    void doesNotSupportEquals() {
        assertFalse(evaluator.supports(new EvaluationCriteria.TextMatch.Equals("x", false)));
    }

    @Test
    void findModeMatchesSubstring() {
        var criteria = new Regex("\\d+", Regex.MatchMode.FIND);
        EvaluationVerdict v = evaluator.evaluate(reply("foo 42 bar"), criteria);
        assertTrue(v.passed());
        assertEquals(1.0, v.score());
    }

    @Test
    void fullMatchFailsOnSubstring() {
        var criteria = new Regex("\\d+", Regex.MatchMode.FULL_MATCH);
        EvaluationVerdict v = evaluator.evaluate(reply("foo 42 bar"), criteria);
        assertFalse(v.passed());
        assertEquals(0.0, v.score());
    }

    @Test
    void fullMatchPassesOnExactMatch() {
        var criteria = new Regex("\\d+", Regex.MatchMode.FULL_MATCH);
        EvaluationVerdict v = evaluator.evaluate(reply("42"), criteria);
        assertTrue(v.passed());
        assertEquals(1.0, v.score());
    }

    @Test
    void invalidRegexReturnsFalseVerdictWithoutThrowing() {
        var criteria = new Regex("[", Regex.MatchMode.FIND);
        EvaluationVerdict v = evaluator.evaluate(reply("anything"), criteria);
        assertFalse(v.passed());
        assertTrue(v.rationale().startsWith("Invalid regex"));
    }

    @Test
    void verdictMetadataContainsMode() {
        var criteria = new Regex("\\d+", Regex.MatchMode.FIND);
        EvaluationVerdict v = evaluator.evaluate(reply("42"), criteria);
        assertEquals("FIND", v.details().get("mode"));
    }
}
