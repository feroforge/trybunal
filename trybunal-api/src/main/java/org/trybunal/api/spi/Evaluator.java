package org.trybunal.api.spi;

import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationResult;

/**
 * SPI implemented by every evaluator (code-based assertions, LLM-as-judge, ...).
 *
 * <p><b>Contract.</b> Implementations MUST be thread-safe; the orchestrator
 * may call them concurrently from virtual threads. Implementations SHOULD NOT
 * add cross-cutting concerns (timing, slf4j request-lifecycle logging, retries).
 * Those belong in the orchestrator.</p>
 *
 * <p>Implementations MUST register under
 * {@code META-INF/services/org.trybunal.api.spi.Evaluator}.</p>
 */
public interface Evaluator {

    /** Stable identifier, e.g. {@code "contains"}, {@code "regex"}, {@code "llm-judge"}. Never null/blank. */
    String id();

    /** True iff this evaluator can grade {@code criteria}. The orchestrator routes by this. */
    boolean supports(EvaluationCriteria criteria);

    /**
     * Grade {@code result} against {@code criteria}.
     *
     * @param result   model's reply + metadata; never null
     * @param criteria criterion to apply; must be {@link #supports(EvaluationCriteria) supported}
     * @return verdict; never null
     * @throws IllegalArgumentException if {@code criteria} is not supported
     */
    EvaluationVerdict evaluate(InvocationResult result, EvaluationCriteria criteria);
}
