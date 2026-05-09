package org.trybunal.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.eval.EvaluationCase;
import org.trybunal.api.eval.EvaluationCriteria;
import org.trybunal.api.eval.EvaluationReport;
import org.trybunal.api.eval.EvaluationVerdict;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.Evaluator;
import org.trybunal.api.spi.ModelHarness;
import org.trybunal.api.spi.ModelProvider;

/**
 * Trybunal's central orchestrator. Holds the registry of {@link ModelProvider}
 * implementations (discovered via {@link ServiceLoader} by default), wraps each
 * one in a {@link ModelHarness}, and dispatches user turns onto a virtual-thread
 * executor.
 *
 * <p>This class is the only stateful "engine" in the runtime. It is safe for
 * concurrent use; underlying providers and harnesses are required to be
 * thread-safe.</p>
 */
public final class Orchestrator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final Map<String, ModelHarness> harnesses;
    private final List<Evaluator> evaluators;
    private final ExecutorService executor;

    private Orchestrator(Map<String, ModelHarness> harnesses, List<Evaluator> evaluators) {
        this.harnesses = Map.copyOf(harnesses);
        this.evaluators = List.copyOf(evaluators);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Discovers all registered {@link ModelProvider}s and {@link Evaluator}s via {@link ServiceLoader}. */
    public static Orchestrator autoDiscover() {
        var byId = new HashMap<String, ModelHarness>();
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
            log.info("registered provider id={} class={}", p.id(), p.getClass().getName());
            byId.put(p.id(), new DefaultModelHarness(p));
        }
        if (byId.isEmpty()) {
            log.warn("no ModelProvider implementations discovered on the classpath");
        }
        var evs = new ArrayList<Evaluator>();
        for (Evaluator e : ServiceLoader.load(Evaluator.class)) {
            log.info("registered evaluator id={} class={}", e.id(), e.getClass().getName());
            evs.add(e);
        }
        return new Orchestrator(byId, evs);
    }

    /** Builds an orchestrator from an explicit provider list (test-friendly). */
    public static Orchestrator of(ModelProvider... providers) {
        var byId = new HashMap<String, ModelHarness>();
        for (ModelProvider p : providers) {
            byId.put(Objects.requireNonNull(p.id(), "provider id"), new DefaultModelHarness(p));
        }
        return new Orchestrator(byId, List.of());
    }

    /**
     * Builds an orchestrator from explicit provider and evaluator lists (test-friendly).
     *
     * @param providers  model providers; defensively copied
     * @param evaluators evaluators; defensively copied
     */
    public static Orchestrator of(List<ModelProvider> providers, List<Evaluator> evaluators) {
        var byId = new HashMap<String, ModelHarness>();
        for (ModelProvider p : providers) {
            byId.put(Objects.requireNonNull(p.id(), "provider id"), new DefaultModelHarness(p));
        }
        return new Orchestrator(byId, evaluators);
    }

    /**
     * Send {@code userMessage} to {@code modelId} within {@code session},
     * dispatching the call on a virtual thread.
     */
    public InvocationResult chat(PromptSession session, ModelId modelId, String userMessage) {
        if (session == null) throw new IllegalArgumentException("session required");
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (userMessage == null) throw new IllegalArgumentException("userMessage required");

        ModelHarness harness = harnesses.get(modelId.provider());
        if (harness == null) {
            throw new IllegalStateException(
                    "no provider registered for id=" + modelId.provider()
                            + "; known=" + harnesses.keySet());
        }

        var conversation = new ArrayList<Message>(session.materialize(modelId));
        conversation.add(new Message.User(userMessage));
        var frozen = List.copyOf(conversation);

        try {
            return executor.submit(
                    () -> harness.run(frozen, modelId, session.params())
            ).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("orchestrator interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("provider call failed", cause);
        }
    }

    private Evaluator findEvaluator(EvaluationCriteria criteria) {
        for (Evaluator e : evaluators) {
            if (e.supports(criteria)) return e;
        }
        throw new IllegalStateException("no evaluator registered for " + criteria.getClass().getName());
    }

    /**
     * Send {@code aCase.userMessage()} to {@code modelId} and grade the reply
     * against {@code aCase.criteria()}.
     *
     * @param session  prompt session; non-null
     * @param modelId  target model; non-null
     * @param aCase    evaluation case; non-null
     * @return verdict from the matching evaluator; never null
     * @throws IllegalStateException if no evaluator supports the criteria
     */
    public EvaluationVerdict evaluate(PromptSession session, ModelId modelId, EvaluationCase aCase) {
        log.debug("evaluate case={} criteria={}", aCase.name(), aCase.criteria().getClass().getSimpleName());
        Evaluator evaluator = findEvaluator(aCase.criteria());
        InvocationResult invocation = chat(session, modelId, aCase.userMessage());
        return evaluator.evaluate(invocation, aCase.criteria());
    }

    /**
     * Run all {@code cases} in parallel on the virtual-thread executor, collect
     * results in input order, and return an {@link EvaluationReport}.
     *
     * @param session  prompt session; non-null
     * @param modelId  target model; non-null
     * @param cases    evaluation cases; non-null
     * @return report containing per-case results in input order; never null
     * @throws IllegalStateException    if any case has unsupported criteria
     * @throws RuntimeException         if the executor is interrupted or a case fails
     */
    public EvaluationReport evaluateAll(PromptSession session, ModelId modelId, List<EvaluationCase> cases) {
        log.debug("evaluateAll cases={}", cases.size());
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        List<Future<EvaluationReport.CaseResult>> futures = new ArrayList<>(cases.size());
        for (EvaluationCase aCase : cases) {
            futures.add(executor.submit(() -> runOne(session, modelId, aCase)));
        }
        List<EvaluationReport.CaseResult> results = new ArrayList<>(cases.size());
        for (Future<EvaluationReport.CaseResult> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("evaluateAll interrupted", e);
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException re) throw re;
                throw new RuntimeException("evaluation failed", c);
            }
        }
        return new EvaluationReport(startedAt, Duration.ofNanos(System.nanoTime() - startNanos), results);
    }

    private EvaluationReport.CaseResult runOne(PromptSession session, ModelId modelId, EvaluationCase aCase) {
        Evaluator evaluator = findEvaluator(aCase.criteria());
        InvocationResult invocation = chat(session, modelId, aCase.userMessage());
        EvaluationVerdict verdict = evaluator.evaluate(invocation, aCase.criteria());
        return new EvaluationReport.CaseResult(aCase, invocation, verdict);
    }

    /** Provider ids currently registered. */
    public java.util.Set<String> registeredProviders() {
        return harnesses.keySet();
    }

    @Override
    public void close() {
        executor.close();
    }
}
