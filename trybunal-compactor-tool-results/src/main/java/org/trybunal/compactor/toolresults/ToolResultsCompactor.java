package org.trybunal.compactor.toolresults;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.spi.CompactionRequest;
import org.trybunal.api.spi.CompactionResult;
import org.trybunal.api.spi.ConversationCompactor;

/**
 * Default {@link ConversationCompactor}. Walks the conversation
 * oldest-to-newest and rewrites each {@link Message.Tool} whose content
 * exceeds {@link #KEEP_VERBATIM_CHARS} to a short summary stub of the
 * form {@code [compacted: <toolName> result, ~<N> chars omitted]}.
 *
 * <p><b>What is always preserved verbatim:</b>
 * <ul>
 *   <li>The system message (if present at index 0).</li>
 *   <li>The first {@link Message.User} message.</li>
 *   <li>The most recent assistant turn that issued tool calls and the
 *       tool messages that pair with it.</li>
 * </ul>
 *
 * <p><b>Idempotent.</b> Stubs are well below {@link #KEEP_VERBATIM_CHARS}
 * and are also recognised explicitly, so a second pass is a no-op.</p>
 *
 * <p><b>No model calls.</b> Token estimation is {@code chars / 4}.</p>
 */
public final class ToolResultsCompactor implements ConversationCompactor {

    /** Stable id used for {@code -Dtrybunal.compactor=...} selection. */
    public static final String ID = "tool-results";

    /** Tool-result content at or below this many chars is left untouched. */
    static final int KEEP_VERBATIM_CHARS = 800;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ToolResultsCompactor() {}

    @Override
    public String id() { return ID; }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        List<Message> input = request.conversation();
        int n = input.size();

        Set<Integer> protectedIdx = protectedIndices(input);

        List<Message> out = new ArrayList<>(input);
        int rewritten = 0;
        long tokensFreed = 0;
        int target = request.targetHeadroomTokens();

        for (int i = 0; i < n; i++) {
            if (tokensFreed >= target) break;
            if (protectedIdx.contains(i)) continue;
            if (!(input.get(i) instanceof Message.Tool tm)) continue;
            String content = tm.content();
            if (content.length() <= KEEP_VERBATIM_CHARS) continue;
            if (isCompactedStub(content)) continue;

            String toolName = recoverToolName(input, i, tm.toolCallId());
            int omitted = content.length();
            String stub = "[compacted: " + toolName + " result, ~" + omitted + " chars omitted]";
            out.set(i, new Message.Tool(tm.toolCallId(), stub));
            tokensFreed += Math.max(0L, (long) (omitted - stub.length()) / 4L);
            rewritten++;
        }

        return new CompactionResult(out, rewritten, 0, tokensFreed);
    }

    /**
     * Collects message indices the compactor must NOT rewrite: the system
     * message (if at index 0), the first user message, and the most recent
     * assistant turn that issued tool calls together with the tool messages
     * that follow it.
     */
    private static Set<Integer> protectedIndices(List<Message> conv) {
        Set<Integer> idx = new HashSet<>();
        int n = conv.size();
        if (n == 0) return idx;

        if (conv.get(0) instanceof Message.System) {
            idx.add(0);
        }
        for (int i = 0; i < n; i++) {
            if (conv.get(i) instanceof Message.User) {
                idx.add(i);
                break;
            }
        }
        // Most recent assistant-with-toolcalls + its trailing tool messages.
        for (int i = n - 1; i >= 0; i--) {
            if (conv.get(i) instanceof Message.Assistant a && !a.toolCalls().isEmpty()) {
                idx.add(i);
                for (int j = i + 1; j < n && conv.get(j) instanceof Message.Tool; j++) {
                    idx.add(j);
                }
                break;
            }
        }
        // Last message overall, regardless of role — keeps any trailing
        // final-answer assistant turn intact.
        idx.add(n - 1);
        return idx;
    }

    private static boolean isCompactedStub(String content) {
        return content.startsWith("[compacted: ") && content.endsWith(" chars omitted]");
    }

    private static String recoverToolName(List<Message> conv, int toolIdx, String toolCallId) {
        for (int i = toolIdx - 1; i >= 0; i--) {
            if (conv.get(i) instanceof Message.Assistant a) {
                for (ToolCall tc : a.toolCalls()) {
                    if (toolCallId.equals(tc.id())) return tc.toolName();
                }
            }
        }
        return "unknown";
    }
}
