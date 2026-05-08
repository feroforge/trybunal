package org.trybunal.evaluator.assertions;

import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.spi.Evaluator;

public final class ContainsEvaluator implements Evaluator {

    public static final String ID = "contains";

    public ContainsEvaluator() {}

    @Override
    public String id() { return ID; }

    @Override
    public boolean supports(EvaluationCriteria c) {
        return c instanceof EvaluationCriteria.TextMatch.Contains;
    }

    @Override
    public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
        if (!(c instanceof EvaluationCriteria.TextMatch.Contains contains))
            throw new IllegalArgumentException("unsupported criteria: " + c);
        String haystack = r.reply().content();
        boolean hit = contains.caseSensitive()
                ? haystack.contains(contains.needle())
                : haystack.toLowerCase().contains(contains.needle().toLowerCase());
        String rationale = hit
                ? "Output contained \"" + contains.needle() + "\""
                : "Output did NOT contain \"" + contains.needle() + "\"";
        return new EvaluationVerdict(hit, hit ? 1.0 : 0.0, ID, rationale, java.util.Map.of());
    }
}
