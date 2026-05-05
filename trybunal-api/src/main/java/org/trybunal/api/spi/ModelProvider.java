package org.trybunal.api.spi;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

/**
 * SPI implemented by every backend (Ollama, OpenAI, Anthropic, ...).
 *
 * <p><b>Contract.</b> Implementations MUST be thread-safe; the orchestrator may
 * invoke them concurrently from virtual threads. Implementations SHOULD NOT
 * add cross-cutting concerns such as timing, slf4j logging of request
 * lifecycle, or retries — those belong in the {@link ModelHarness} layer.</p>
 *
 * <p>Implementations MUST register themselves under
 * {@code META-INF/services/org.trybunal.api.spi.ModelProvider} so the
 * orchestrator can discover them via {@link java.util.ServiceLoader}.</p>
 */
public interface ModelProvider {

    /** Stable identifier, e.g. {@code "ollama"}. Must equal {@link ModelId#provider()}. */
    String id();

    /** True if this provider can serve {@code modelId}. */
    boolean supports(ModelId modelId);

    /**
     * Synchronous chat-completion. Implementations may block on I/O — callers
     * are expected to dispatch on virtual threads.
     *
     * @param conversation full conversation prefix; never null, never empty
     * @param modelId      target model; must be {@link #supports(ModelId) supported}
     * @param params       generation knobs; never null
     * @return reply + provider-reported metadata; never null
     */
    InvocationResult invoke(List<Message> conversation, ModelId modelId, GenerationParams params);

    /**
     * Default async wrapper. Override only when the underlying client has a
     * native async API worth surfacing.
     */
    default CompletableFuture<InvocationResult> invokeAsync(
            List<Message> conversation, ModelId modelId, GenerationParams params) {
        return CompletableFuture.supplyAsync(() -> invoke(conversation, modelId, params));
    }
}
