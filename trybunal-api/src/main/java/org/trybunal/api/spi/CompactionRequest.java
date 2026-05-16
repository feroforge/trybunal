package org.trybunal.api.spi;

import java.util.List;
import org.trybunal.api.model.ContextWindow;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

/**
 * Input to a {@link ConversationCompactor#compact(CompactionRequest)} call.
 *
 * <p>The harness assembles this just before delegating to a provider when
 * {@link ContextWindow#headroom()} on the previous turn drops below the
 * configured threshold. The compactor receives the conversation it would
 * otherwise have sent; the harness does not re-tokenise or re-fetch
 * headroom afterwards.</p>
 *
 * @param conversation         conversation the harness is about to send;
 *                             never null; defensively copied
 * @param currentWindow        latest {@link ContextWindow} reported by the
 *                             provider, or {@code null} when unknown
 *                             (first turn, or provider does not report)
 * @param targetHeadroomTokens tokens the compactor should aim to free;
 *                             must be {@code >= 0}
 * @param modelId              target model for the upcoming call; never null
 */
public record CompactionRequest(
        List<Message> conversation,
        ContextWindow currentWindow,
        int targetHeadroomTokens,
        ModelId modelId) {

    public CompactionRequest {
        if (conversation == null) throw new IllegalArgumentException("conversation required");
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (targetHeadroomTokens < 0)
            throw new IllegalArgumentException("targetHeadroomTokens must be >= 0");
        conversation = List.copyOf(conversation);
    }
}
