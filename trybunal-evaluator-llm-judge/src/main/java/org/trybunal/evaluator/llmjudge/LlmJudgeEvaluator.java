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
        return c instanceof EvaluationCriteria.LlmRubric;
    }

    /**
     * Evaluates {@code result} against {@code criteria}.
     *
     * @param result   the model output to grade; never null
     * @param criteria must be {@link EvaluationCriteria.LlmRubric}
     * @return a verdict; never null; {@code passed=false} on any failure
     * @throws IllegalArgumentException if {@code criteria} is not an {@link EvaluationCriteria.LlmRubric}
     */
    @Override
    public EvaluationVerdict evaluate(InvocationResult result, EvaluationCriteria criteria) {
        if (!(criteria instanceof EvaluationCriteria.LlmRubric rubric))
            throw new IllegalArgumentException("unsupported criteria: " + criteria);

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
                    new GenerationParams(0.0, 512, null, null, Map.of()));
        } catch (RuntimeException e) {
            log.warn("judge invocation failed: {}", e.toString());
            return new EvaluationVerdict(false, 0.0, ID,
                    "Judge invocation failed: " + e.getMessage(), Map.of());
        }
        return parseVerdict(judged.reply().content());
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

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
