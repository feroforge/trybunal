package org.trybunal.api.model;

/**
 * Pairs the model's reply with the metadata captured around the call.
 *
 * @param reply    assistant message returned by the model; never null
 * @param metadata captured invocation metadata; never null
 */
public record InvocationResult(Message.Assistant reply, InvocationMetadata metadata) {
    public InvocationResult {
        if (reply == null) throw new IllegalArgumentException("reply required");
        if (metadata == null) throw new IllegalArgumentException("metadata required");
    }
}
