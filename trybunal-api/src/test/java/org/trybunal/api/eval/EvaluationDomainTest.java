package org.trybunal.api.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

class EvaluationDomainTest {

    private static final ModelId MODEL = new ModelId("ollama", "llama3.1:8b");

    private static EvaluationCriteria rubric() {
        return new EvaluationCriteria.LlmRubric("be helpful", MODEL);
    }

    private static InvocationResult invocation() {
        return new InvocationResult(
                Message.Assistant.of("hi"),
                new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                        null, null, List.of(), null));
    }

    private static EvaluationReport.CaseResult caseResult(boolean passed) {
        var c = new EvaluationCase("c", "hello", rubric());
        var v = new EvaluationVerdict(passed, passed ? 1.0 : 0.0, "demo", "ok", Map.of());
        return new EvaluationReport.CaseResult(c, invocation(), v);
    }

    @Test
    void verdictRejectsNaNScore() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationVerdict(false, Double.NaN, "demo", "", Map.of()));
    }

    @Test
    void verdictRejectsNegativeScore() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationVerdict(false, -0.01, "demo", "", Map.of()));
    }

    @Test
    void verdictRejectsScoreAboveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationVerdict(true, 1.0001, "demo", "", Map.of()));
    }

    @Test
    void verdictNullRationaleBecomesEmpty() {
        var v = new EvaluationVerdict(true, 1.0, "demo", null, Map.of());
        assertEquals("", v.rationale());
    }

    @Test
    void verdictNullDetailsBecomesEmptyMap() {
        var v = new EvaluationVerdict(true, 1.0, "demo", "", null);
        assertNotNull(v.details());
        assertTrue(v.details().isEmpty());
    }

    @Test
    void verdictRejectsBlankEvaluatorId() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationVerdict(true, 1.0, "  ", "", Map.of()));
    }

    @Test
    void caseRejectsNullName() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCase(null, "msg", rubric()));
    }

    @Test
    void caseRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCase(" ", "msg", rubric()));
    }

    @Test
    void caseRejectsNullUserMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCase("c", null, rubric()));
    }

    @Test
    void caseRejectsNullCriteria() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCase("c", "msg", null));
    }

    @Test
    void reportArithmetic() {
        var r = new EvaluationReport(Instant.EPOCH, Duration.ofMillis(10),
                List.of(caseResult(true), caseResult(false), caseResult(true)));
        assertEquals(2, r.passCount());
        assertEquals(1, r.failCount());
        assertFalse(r.allPassed());
    }

    @Test
    void reportAllPassed() {
        var r = new EvaluationReport(Instant.EPOCH, Duration.ZERO,
                List.of(caseResult(true), caseResult(true)));
        assertTrue(r.allPassed());
        assertEquals(2, r.passCount());
        assertEquals(0, r.failCount());
    }

    @Test
    void reportEmptyAllPassedVacuous() {
        var r = new EvaluationReport(Instant.EPOCH, Duration.ZERO, List.of());
        assertTrue(r.allPassed());
        assertEquals(0, r.passCount());
    }

    @Test
    void reportNullResultsBecomesEmpty() {
        var r = new EvaluationReport(Instant.EPOCH, Duration.ZERO, null);
        assertNotNull(r.results());
        assertTrue(r.results().isEmpty());
    }

    @Test
    void reportResultsDefensivelyCopied() {
        var list = new java.util.ArrayList<EvaluationReport.CaseResult>();
        list.add(caseResult(true));
        var r = new EvaluationReport(Instant.EPOCH, Duration.ZERO, list);
        list.add(caseResult(false));
        assertEquals(1, r.results().size());
    }

    @Test
    void llmRubricRejectsBlankRubric() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCriteria.LlmRubric("  ", MODEL));
    }

    @Test
    void llmRubricRejectsNullRubric() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCriteria.LlmRubric(null, MODEL));
    }

    @Test
    void llmRubricRejectsNullModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvaluationCriteria.LlmRubric("be helpful", null));
    }
}
