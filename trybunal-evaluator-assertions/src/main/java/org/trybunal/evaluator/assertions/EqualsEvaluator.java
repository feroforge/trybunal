package org.trybunal.evaluator.assertions;

import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.spi.Evaluator;

public final class EqualsEvaluator implements Evaluator {

    public static final String ID = "equals";

    public EqualsEvaluator() {}

    @Override
    public String id() { return ID; }

    @Override
    public boolean supports(EvaluationCriteria c) {
        return c instanceof EvaluationCriteria.TextMatch.Equals;
    }

    @Override
    public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
        if (!(c instanceof EvaluationCriteria.TextMatch.Equals eq))
            throw new IllegalArgumentException("unsupported criteria: " + c);
        String haystack = r.reply().content();
        if (eq.trim()) haystack = haystack.strip();
        boolean hit = haystack.equals(eq.expected());
        String rationale = hit
                ? "Output equalled expected value"
                : "Output did not equal expected. Expected: \"" + truncate(eq.expected())
                  + "\" but got: \"" + truncate(haystack) + "\"";
        return new EvaluationVerdict(hit, hit ? 1.0 : 0.0, ID, rationale, java.util.Map.of());
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
