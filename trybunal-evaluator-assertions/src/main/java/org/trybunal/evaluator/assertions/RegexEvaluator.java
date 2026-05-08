package org.trybunal.evaluator.assertions;

import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.spi.Evaluator;

public final class RegexEvaluator implements Evaluator {

    public static final String ID = "regex";

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern> cache
            = new java.util.concurrent.ConcurrentHashMap<>();

    public RegexEvaluator() {}

    @Override public String id() { return ID; }

    @Override public boolean supports(EvaluationCriteria c) {
        return c instanceof EvaluationCriteria.TextMatch.Regex;
    }

    @Override public EvaluationVerdict evaluate(InvocationResult r, EvaluationCriteria c) {
        if (!(c instanceof EvaluationCriteria.TextMatch.Regex rx))
            throw new IllegalArgumentException("unsupported criteria: " + c);
        java.util.regex.Pattern p;
        try {
            p = cache.computeIfAbsent(rx.pattern(), java.util.regex.Pattern::compile);
        } catch (java.util.regex.PatternSyntaxException e) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "Invalid regex: " + e.getMessage(), java.util.Map.of());
        }
        var matcher = p.matcher(r.reply().content());
        boolean hit = switch (rx.mode()) {
            case FIND -> matcher.find();
            case FULL_MATCH -> matcher.matches();
        };
        return new EvaluationVerdict(
                hit, hit ? 1.0 : 0.0, ID,
                hit ? "Output matched /" + rx.pattern() + "/"
                    : "Output did not match /" + rx.pattern() + "/",
                java.util.Map.of("mode", rx.mode().name()));
    }
}
