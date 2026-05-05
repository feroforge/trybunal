package org.trybunal.api.model;

import java.util.List;

/**
 * A single chat message. Sealed: the four canonical roles are the only valid kinds.
 *
 * <p>Using a sealed interface (rather than an enum + payload) lets pattern-matching
 * code be exhaustive at compile time, which is critical for both human and LLM
 * readability.</p>
 */
public sealed interface Message
        permits Message.System, Message.User, Message.Assistant, Message.Tool {

    /** The textual content of the message. Never null; may be empty. */
    String content();

    /** A system / instruction message that anchors the conversation. */
    record System(String content) implements Message {
        public System {
            if (content == null) throw new IllegalArgumentException("content required");
        }
    }

    /** A turn produced by the end user. */
    record User(String content) implements Message {
        public User {
            if (content == null) throw new IllegalArgumentException("content required");
        }
    }

    /**
     * A turn produced by the model. May contain {@code toolCalls} requesting
     * tool invocations; in that case {@code content} is often empty.
     */
    record Assistant(String content, List<ToolCall> toolCalls) implements Message {
        public Assistant {
            if (content == null) content = "";
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Assistant of(String content) {
            return new Assistant(content, List.of());
        }
    }

    /** A response to a prior {@link ToolCall}. {@code toolCallId} echoes the call's id. */
    record Tool(String toolCallId, String content) implements Message {
        public Tool {
            if (toolCallId == null) throw new IllegalArgumentException("toolCallId required");
            if (content == null) throw new IllegalArgumentException("content required");
        }
    }
}
