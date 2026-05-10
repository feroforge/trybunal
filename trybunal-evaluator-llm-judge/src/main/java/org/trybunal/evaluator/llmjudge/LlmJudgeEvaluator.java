package org.trybunal.evaluator.llmjudge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.Evaluator;
import org.trybunal.api.spi.ModelProvider;

/**
 * LLM-as-judge {@link Evaluator} that grades model output against a free-text rubric.
 *
 * <p>Supports two construction paths:</p>
 * <ul>
 *   <li><b>No-arg</b> — required by {@link ServiceLoader}. Resolves a {@link ModelProvider}
 *       lazily via {@code ServiceLoader} on the first {@link #evaluate} call, matching on
 *       {@code judgeModel.provider()}. If no provider is found, returns a {@code passed=false}
 *       verdict; never throws.</li>
 *   <li><b>Programmatic</b> — {@link #LlmJudgeEvaluator(ModelProvider)} injects the provider
 *       directly. Preferred for tests and embedded use.</li>
 * </ul>
 *
 * <p>All judge failures (missing provider, transport error, malformed JSON) are returned as
 * {@code passed=false} verdicts — no exceptions escape to callers.</p>
 */
public final class LlmJudgeEvaluator implements Evaluator {

    public static final String ID = "llm-judge";

    /**
     * System property used to override the per-call {@code maxTokens} budget
     * given to the judge model. Default {@value #DEFAULT_MAX_TOKENS}. Bump
     * this when grading reasoning models that consume tokens in a separate
     * thinking channel before the JSON verdict appears.
     */
    public static final String MAX_TOKENS_PROPERTY = "trybunal.judge.maxTokens";
    public static final int DEFAULT_MAX_TOKENS = 2048;

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeEvaluator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Non-null when constructed programmatically; null when ServiceLoader-constructed. */
    private final ModelProvider injectedProvider;

    /** Cache of discovered providers, keyed by provider id string. */
    private final ConcurrentHashMap<String, ModelProvider> discovered = new ConcurrentHashMap<>();

    /** No-arg constructor for {@link ServiceLoader}. Provider resolved lazily on first call. */
    public LlmJudgeEvaluator() {
        this.injectedProvider = null;
    }

    /**
     * Programmatic constructor — preferred for tests and embedded use.
     *
     * @param judge the {@link ModelProvider} to use for grading; must not be null
     */
    public LlmJudgeEvaluator(ModelProvider judge) {
        if (judge == null) throw new IllegalArgumentException("judge required");
        this.injectedProvider = judge;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supports(EvaluationCriteria c) {
        return c instanceof EvaluationCriteria.LlmRubric
                || c instanceof EvaluationCriteria.LlmRubricChecklist;
    }

    /**
     * Evaluates {@code result} against {@code criteria}.
     *
     * @param result   the model output to grade; never null
     * @param criteria must be {@link EvaluationCriteria.LlmRubric} or
     *                 {@link EvaluationCriteria.LlmRubricChecklist}
     * @return a verdict; never null; {@code passed=false} on any failure
     * @throws IllegalArgumentException if {@code criteria} is unsupported
     */
    @Override
    public EvaluationVerdict evaluate(InvocationResult result, EvaluationCriteria criteria) {
        // Sealed-exhaustive over EvaluationCriteria's three permits — the
        // TextMatch arm exists so adding a fourth permit becomes a compile
        // error here, not an IllegalArgumentException at runtime.
        return switch (criteria) {
            case EvaluationCriteria.LlmRubric rubric        -> evaluateRubric(result, rubric);
            case EvaluationCriteria.LlmRubricChecklist list -> evaluateChecklist(result, list);
            case EvaluationCriteria.TextMatch tm ->
                    throw new IllegalArgumentException("unsupported criteria: " + tm);
        };
    }

    private EvaluationVerdict evaluateRubric(
            InvocationResult result, EvaluationCriteria.LlmRubric rubric) {
        ModelProvider judge = resolveProvider(rubric.judgeModel());
        if (judge == null) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "No ModelProvider registered for judge id=" + rubric.judgeModel().provider(),
                    Map.of());
        }

        var conversation = JudgePromptTemplate.render(rubric.rubric(), result.reply().content());
        InvocationResult judged;
        try {
            judged = judge.invoke(conversation, rubric.judgeModel(),
                    new GenerationParams(0.0, judgeMaxTokens(), null, null, Map.of()));
        } catch (RuntimeException e) {
            log.warn("judge invocation failed: {}", e.toString());
            return new EvaluationVerdict(false, 0.0, ID,
                    "Judge invocation failed: " + e.getMessage(), Map.of());
        }
        return parseVerdict(judged.reply().content());
    }

    private EvaluationVerdict evaluateChecklist(
            InvocationResult result, EvaluationCriteria.LlmRubricChecklist list) {
        ModelProvider judge = resolveProvider(list.judgeModel());
        if (judge == null) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "No ModelProvider registered for judge id=" + list.judgeModel().provider(),
                    Map.of());
        }

        var conversation = JudgePromptTemplate.renderChecklist(
                list.checks(), result.reply().content());
        InvocationResult judged;
        try {
            judged = judge.invoke(conversation, list.judgeModel(),
                    new GenerationParams(0.0, judgeMaxTokens(), null, null, Map.of()));
        } catch (RuntimeException e) {
            log.warn("judge invocation failed: {}", e.toString());
            return new EvaluationVerdict(false, 0.0, ID,
                    "Judge invocation failed: " + e.getMessage(), Map.of());
        }
        return parseChecklistVerdict(list.checks(), judged.reply().content());
    }

    private ModelProvider resolveProvider(ModelId judgeModel) {
        if (injectedProvider != null && injectedProvider.supports(judgeModel))
            return injectedProvider;
        return discovered.computeIfAbsent(judgeModel.provider(), id -> {
            for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
                if (id.equals(p.id())) return p;
            }
            return null;
        });
    }

    private static EvaluationVerdict parseVerdict(String raw) {
        String json = JudgePromptTemplate.extractJsonBlock(raw);
        if (json == null) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "Judge returned no JSON block. Raw: " + truncate(raw, 300),
                    Map.of("raw", raw));
        }
        try {
            JsonNode root = JSON.readTree(json);
            boolean passed = root.path("passed").asBoolean(false);
            double score = root.path("score").asDouble(passed ? 1.0 : 0.0);
            if (Double.isNaN(score)) score = passed ? 1.0 : 0.0;
            score = Math.max(0.0, Math.min(1.0, score));
            String rationaleRaw = root.path("rationale").asText();
            String rationale = rationaleRaw.isEmpty() ? "(no rationale)" : rationaleRaw;
            return new EvaluationVerdict(passed, score, ID, rationale, Map.of("raw", raw));
        } catch (Exception e) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "Failed to parse judge JSON: " + e.getMessage(),
                    Map.of("raw", raw));
        }
    }

    /**
     * Parses a checklist verdict. Pass requires every check to be true. Score
     * is {@code passing / total} so partial-pass cases still surface signal.
     * The full per-check breakdown is stashed in {@code details["checks"]}
     * for downstream renderers.
     */
    private static EvaluationVerdict parseChecklistVerdict(java.util.List<String> checks, String raw) {
        String json = JudgePromptTemplate.extractJsonBlock(raw);
        if (json == null) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "Judge returned no JSON block. Raw: " + truncate(raw, 300),
                    Map.of("raw", raw));
        }
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return new EvaluationVerdict(false, 0.0, ID,
                        "Judge JSON missing non-empty 'results' array",
                        Map.of("raw", raw));
            }
            int total = checks.size();
            int passing = 0;
            var perCheck = new java.util.ArrayList<Map<String, Object>>(total);
            // Iterate the model's results; clamp to expected count.
            for (int i = 0; i < Math.min(results.size(), total); i++) {
                JsonNode r = results.get(i);
                boolean p = r.path("passed").asBoolean(false);
                String rationale = r.path("rationale").asText();
                if (p) passing++;
                perCheck.add(Map.of(
                        "index", i + 1,
                        "check", checks.get(i),
                        "passed", p,
                        "rationale", rationale.isEmpty() ? "(no rationale)" : rationale));
            }
            // Treat any short array as failure on the missing checks.
            for (int i = results.size(); i < total; i++) {
                perCheck.add(Map.of(
                        "index", i + 1,
                        "check", checks.get(i),
                        "passed", false,
                        "rationale", "Judge omitted this check"));
            }
            boolean allPassed = passing == total;
            double score = total == 0 ? 0.0 : (double) passing / total;
            String summary = allPassed
                    ? "All " + total + " checks passed."
                    : passing + " / " + total + " checks passed.";
            return new EvaluationVerdict(allPassed, score, ID, summary,
                    Map.of("raw", raw, "checks", perCheck));
        } catch (Exception e) {
            return new EvaluationVerdict(false, 0.0, ID,
                    "Failed to parse judge JSON: " + e.getMessage(),
                    Map.of("raw", raw));
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * Resolves the judge call's {@code maxTokens} from
     * {@link #MAX_TOKENS_PROPERTY}, falling back to {@link #DEFAULT_MAX_TOKENS}
     * when unset or unparseable. Read on every call so reasoning models can
     * be given more headroom without restarting the JVM.
     */
    private static int judgeMaxTokens() {
        String raw = System.getProperty(MAX_TOKENS_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_TOKENS;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : DEFAULT_MAX_TOKENS;
        } catch (NumberFormatException e) {
            log.warn("invalid {} value: {}; falling back to default {}",
                    MAX_TOKENS_PROPERTY, raw, DEFAULT_MAX_TOKENS);
            return DEFAULT_MAX_TOKENS;
        }
    }
}
