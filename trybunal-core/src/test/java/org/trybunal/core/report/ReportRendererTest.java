package org.trybunal.core.report;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.eval.EvaluationReport.CaseResult;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

class ReportRendererTest {

    private static final ModelId MODEL = new ModelId("stub", "m");
    private static final Instant NOW = Instant.now();

    private static CaseResult caseResult(
            String name, String evaluatorId, boolean passed, double score,
            String rationale, String output, long latencyMs) {
        var criteria = new EvaluationCriteria.TextMatch.Contains("x", true);
        var aCase = new EvaluationCase(name, "prompt", criteria);
        var verdict = new EvaluationVerdict(passed, score, evaluatorId, rationale, Map.of());
        var meta = new InvocationMetadata(MODEL, NOW, Duration.ofMillis(latencyMs), null, null, List.of(), "stop");
        var invocation = new InvocationResult(Message.Assistant.of(output), meta);
        return new CaseResult(aCase, invocation, verdict);
    }

    private static EvaluationReport mixedReport() {
        return new EvaluationReport(NOW, Duration.ofMillis(1632), List.of(
                caseResult("greets-by-name",     "contains",  true,  1.00, "",                          "Hello Felix",  412),
                caseResult("uses-greeting-tone", "llm-judge", true,  0.90, "Tone is warm and concise.", "Hi there",     802),
                caseResult("mentions-trybunal",  "regex",     true,  1.00, "Output matched /Tryb\\w+/", "Trybunal!",    220),
                caseResult("avoids-emoji",        "regex",     false, 0.00, "Output matched /\\p{So}/",  "Welcome! 👋",  198)
        ));
    }

    private static EvaluationReport allPassReport() {
        return new EvaluationReport(NOW, Duration.ofMillis(500), List.of(
                caseResult("case-a", "contains", true, 1.00, "matched", "ok", 250),
                caseResult("case-b", "regex",    true, 1.00, "matched", "ok", 250)
        ));
    }

    // ── text() tests ──────────────────────────────────────────────────────────

    @Test
    void textContainsPassFailCounts() {
        String out = ReportRenderer.text(mixedReport());
        assertTrue(out.contains("3 passed / 1 failed"), "header must show pass/fail counts");
    }

    @Test
    void textContainsFailTagAndOutput() {
        String out = ReportRenderer.text(mixedReport());
        assertTrue(out.contains("[FAIL]"), "must contain [FAIL] tag");
        assertTrue(out.contains("Welcome! 👋"), "FAIL case must show output");
    }

    @Test
    void textShowsPassTag() {
        String out = ReportRenderer.text(mixedReport());
        assertTrue(out.contains("[PASS]"), "must contain [PASS] tag");
    }

    @Test
    void textTruncatesOutputAt200Chars() {
        String longOutput = "x".repeat(300);
        var report = new EvaluationReport(NOW, Duration.ofMillis(100), List.of(
                caseResult("too-long", "contains", false, 0.0, "rationale", longOutput, 100)
        ));
        String out = ReportRenderer.text(report);
        assertFalse(out.contains("x".repeat(201)), "output must be truncated to 200 chars");
        assertTrue(out.contains("x".repeat(200)), "200 chars of output must appear");
    }

    @Test
    void textAllPassShowsZeroFailed() {
        String out = ReportRenderer.text(allPassReport());
        assertTrue(out.contains("0 failed"), "all-pass report must end with 0 failed");
    }

    @Test
    void textDoesNotShowOutputForPass() {
        String out = ReportRenderer.text(allPassReport());
        assertFalse(out.contains("output:"), "PASS cases must not show output line");
    }

    // ── markdown() tests ──────────────────────────────────────────────────────

    @Test
    void markdownStartsWithHeader() {
        String out = ReportRenderer.markdown(mixedReport());
        assertTrue(out.startsWith("## Trybunal evaluation"), "must start with ## header");
    }

    @Test
    void markdownContainsTableHeaderRow() {
        String out = ReportRenderer.markdown(mixedReport());
        assertTrue(out.contains("| Case | Evaluator | Result | Score | Latency | Rationale |"),
                "must contain table header row");
    }

    @Test
    void markdownEscapesPipeInRationale() {
        var report = new EvaluationReport(NOW, Duration.ofMillis(100), List.of(
                caseResult("pipe-case", "contains", true, 1.0, "a | b", "output", 50)
        ));
        String out = ReportRenderer.markdown(report);
        assertTrue(out.contains("a \\| b"), "pipe in rationale must be escaped");
        assertFalse(out.contains("a | b"), "unescaped pipe must not appear in rationale cell");
    }

    @Test
    void markdownTruncatesRationaleAt120Chars() {
        String longRationale = "r".repeat(200);
        var report = new EvaluationReport(NOW, Duration.ofMillis(100), List.of(
                caseResult("long-rationale", "llm-judge", true, 1.0, longRationale, "ok", 50)
        ));
        String out = ReportRenderer.markdown(report);
        assertFalse(out.contains("r".repeat(121)), "rationale must be truncated to 120 chars");
        assertTrue(out.contains("r".repeat(120)), "120 chars of rationale must appear");
    }

    @Test
    void markdownAllPassShowsCorrectCount() {
        String out = ReportRenderer.markdown(allPassReport());
        assertTrue(out.contains("2 / 2 passed"), "all-pass markdown must show full pass count");
    }
}
