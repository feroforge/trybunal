package org.trybunal.api.spi;

import java.util.List;
import org.trybunal.api.model.Message;

/**
 * Output of a {@link ConversationCompactor#compact(CompactionRequest)} call.
 *
 * <p>When the compactor made no change, {@code messagesRewritten} and
 * {@code messagesDropped} are both zero and {@code conversation} is the
 * same list (defensively copied) that was supplied on the request.</p>
 *
 * @param conversation             the (possibly-shrunken) conversation to
 *                                 send next; never null; defensively copied
 * @param messagesRewritten        number of messages rewritten in place
 *                                 (e.g. tool results replaced with stubs);
 *                                 must be {@code >= 0}
 * @param messagesDropped          number of messages removed entirely;
 *                                 must be {@code >= 0}
 * @param approximateTokensFreed   best-effort estimate of tokens reclaimed;
 *                                 must be {@code >= 0}
 */
public record CompactionResult(
        List<Message> conversation,
        int messagesRewritten,
        int messagesDropped,
        long approximateTokensFreed) {

    public CompactionResult {
        if (conversation == null) throw new IllegalArgumentException("conversation required");
        if (messagesRewritten < 0)
            throw new IllegalArgumentException("messagesRewritten must be >= 0");
        if (messagesDropped < 0)
            throw new IllegalArgumentException("messagesDropped must be >= 0");
        if (approximateTokensFreed < 0)
            throw new IllegalArgumentException("approximateTokensFreed must be >= 0");
        conversation = List.copyOf(conversation);
    }
}
