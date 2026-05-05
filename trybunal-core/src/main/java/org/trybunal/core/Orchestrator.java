package org.trybunal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
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
    private final ExecutorService executor;

    private Orchestrator(Map<String, ModelHarness> harnesses) {
        this.harnesses = Map.copyOf(harnesses);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Discovers all registered {@link ModelProvider}s via {@link ServiceLoader}. */
    public static Orchestrator autoDiscover() {
        var byId = new HashMap<String, ModelHarness>();
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
            log.info("registered provider id={} class={}", p.id(), p.getClass().getName());
            byId.put(p.id(), new DefaultModelHarness(p));
        }
        if (byId.isEmpty()) {
            log.warn("no ModelProvider implementations discovered on the classpath");
        }
        return new Orchestrator(byId);
    }

    /** Builds an orchestrator from an explicit provider list (test-friendly). */
    public static Orchestrator of(ModelProvider... providers) {
        var byId = new HashMap<String, ModelHarness>();
        for (ModelProvider p : providers) {
            byId.put(Objects.requireNonNull(p.id(), "provider id"), new DefaultModelHarness(p));
        }
        return new Orchestrator(byId);
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

    /** Provider ids currently registered. */
    public java.util.Set<String> registeredProviders() {
        return harnesses.keySet();
    }

    @Override
    public void close() {
        executor.close();
    }
}
