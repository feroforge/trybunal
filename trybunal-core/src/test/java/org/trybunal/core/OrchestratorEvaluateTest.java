package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.Evaluator;
import org.trybunal.api.spi.ModelProvider;

class OrchestratorEvaluateTest {

    private static final ModelId MODEL = new ModelId("stub", "m");

    private static ModelProvider stubProvider(String reply) {
        return new ModelProvider() {
            @Override public String id() { return "stub"; }
            @Override public boolean supports(ModelId m) { return "stub".equals(m.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                return new InvocationResult(
                        Message.Assistant.of(reply),
                        new InvocationMetadata(m, Instant.now(), Duration.ZERO, null, null, List.of(), "stop"));
            }
        };
    }

    private static Evaluator passingEvaluator(String id, EvaluationCriteria supported) {
        return new Evaluator() {
            @Override public String id() { return id; }
            @Override public boolean supports(EvaluationCriteria c) { return c == supported; }
            @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
                return new EvaluationVerdict(true, 1.0, id, "ok", null);
            }
        };
    }

    private static PromptSession session() {
        return PromptSession.of("t", "sys");
    }

    // ── Test 1: evaluate returns the verdict the evaluator produced ──────────

    @Test
    void evaluateReturnsVerdictFromEvaluator() {
        var criteria = new EvaluationCriteria.TextMatch.Contains("hello", true);
        var aCase = new EvaluationCase("c1", "say hello", criteria);
        var evaluator = passingEvaluator("test-ev", criteria);

        try (var orch = Orchestrator.of(List.of(stubProvider("hello world")), List.of(evaluator))) {
            EvaluationVerdict v = orch.evaluate(session(), MODEL, aCase);
            assertTrue(v.passed());
            assertEquals(1.0, v.score());
            assertEquals("test-ev", v.evaluatorId());
        }
    }

    // ── Test 2: evaluateAll preserves input order ────────────────────────────

    @Test
    void evaluateAllPreservesInputOrder() throws Exception {
        var criteria1 = new EvaluationCriteria.TextMatch.Contains("a", true);
        var criteria2 = new EvaluationCriteria.TextMatch.Contains("b", true);
        var case1 = new EvaluationCase("slow", "msg1", criteria1);
        var case2 = new EvaluationCase("fast", "msg2", criteria2);

        // evaluator for case1 sleeps longer so case2 finishes first
        Evaluator slowEv = new Evaluator() {
            @Override public String id() { return "slow-ev"; }
            @Override public boolean supports(EvaluationCriteria c) { return c == criteria1; }
            @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationVerdict(true, 1.0, "slow-ev", "slow", null);
            }
        };
        Evaluator fastEv = new Evaluator() {
            @Override public String id() { return "fast-ev"; }
            @Override public boolean supports(EvaluationCriteria c) { return c == criteria2; }
            @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
                return new EvaluationVerdict(true, 1.0, "fast-ev", "fast", null);
            }
        };

        try (var orch = Orchestrator.of(List.of(stubProvider("x")), List.of(slowEv, fastEv))) {
            EvaluationReport report = orch.evaluateAll(session(), MODEL, List.of(case1, case2));
            assertEquals(2, report.results().size());
            assertEquals("slow", report.results().get(0).aCase().name());
            assertEquals("fast", report.results().get(1).aCase().name());
        }
    }

    // ── Test 3: evaluateAll populates startedAt, totalDuration, results ──────

    @Test
    void evaluateAllPopulatesReportFields() {
        var criteria = new EvaluationCriteria.TextMatch.Equals("hi", false);
        var aCase = new EvaluationCase("c1", "say hi", criteria);
        var evaluator = passingEvaluator("ev", criteria);

        Instant before = Instant.now();
        try (var orch = Orchestrator.of(List.of(stubProvider("hi")), List.of(evaluator))) {
            EvaluationReport report = orch.evaluateAll(session(), MODEL, List.of(aCase));
            assertNotNull(report.startedAt());
            assertFalse(report.startedAt().isBefore(before));
            assertNotNull(report.totalDuration());
            assertFalse(report.totalDuration().isNegative());
            assertEquals(1, report.results().size());
        }
    }

    // ── Test 4: evaluateAll runs in parallel (total < sum of sleeps) ─────────

    @Test
    void evaluateAllRunsInParallel() {
        var criteria1 = new EvaluationCriteria.TextMatch.Contains("a", true);
        var criteria2 = new EvaluationCriteria.TextMatch.Contains("b", true);

        long sleepMs = 200;

        Evaluator ev1 = new Evaluator() {
            @Override public String id() { return "ev1"; }
            @Override public boolean supports(EvaluationCriteria c) { return c == criteria1; }
            @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationVerdict(true, 1.0, "ev1", "", null);
            }
        };
        Evaluator ev2 = new Evaluator() {
            @Override public String id() { return "ev2"; }
            @Override public boolean supports(EvaluationCriteria c) { return c == criteria2; }
            @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new EvaluationVerdict(true, 1.0, "ev2", "", null);
            }
        };

        var case1 = new EvaluationCase("c1", "m1", criteria1);
        var case2 = new EvaluationCase("c2", "m2", criteria2);

        try (var orch = Orchestrator.of(List.of(stubProvider("x")), List.of(ev1, ev2))) {
            EvaluationReport report = orch.evaluateAll(session(), MODEL, List.of(case1, case2));
            long totalMs = report.totalDuration().toMillis();
            // if sequential, totalMs >= 2 * sleepMs; parallel means it's less
            assertTrue(totalMs < 2 * sleepMs,
                    "expected parallel execution but total=%dms >= sum=%dms".formatted(totalMs, 2 * sleepMs));
        }
    }

    // ── Test 5: unsupported criteria → IllegalStateException ─────────────────

    @Test
    void unsupportedCriteriaThrowsIllegalStateException() {
        var criteria = new EvaluationCriteria.TextMatch.Contains("x", true);
        var aCase = new EvaluationCase("c1", "msg", criteria);

        try (var orch = Orchestrator.of(List.of(stubProvider("x")), List.of())) {
            assertThrows(IllegalStateException.class,
                    () -> orch.evaluate(session(), MODEL, aCase));
        }
    }
}
