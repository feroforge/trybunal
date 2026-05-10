package org.trybunal.evaluator.llmjudge;

import java.util.List;
import org.trybunal.api.model.Message;

/**
 * Renders the structured prompt sent to the judge model and extracts the JSON
 * verdict from the judge's reply.
 *
 * <p>All methods are static; this class is not instantiable.</p>
 */
public final class JudgePromptTemplate {

    private JudgePromptTemplate() {}

    private static final String SYSTEM = """
            You are a strict, objective evaluator.
            You will be given a RUBRIC and a CANDIDATE response.
            Decide whether the candidate satisfies the rubric.
            Reply with a SINGLE JSON object on one line and nothing else:
            {"passed": <true|false>, "score": <0.0-1.0>, "rationale": "<one sentence>"}
            """;

    private static final String CHECKLIST_SYSTEM = """
            You are a strict, objective evaluator.
            You will be given a numbered list of CHECKS and a CANDIDATE response.
            For EACH check, decide independently whether the candidate satisfies it.
            Reply with a SINGLE JSON object on one line and nothing else:
            {"results": [{"index": <int>, "passed": <true|false>, "rationale": "<one sentence>"}, ...]}
            Include exactly one result object per check, in the same order.
            """;

    /**
     * Builds the two-message conversation to send to the judge.
     *
     * @param rubric    free-text grading rubric; must not be null
     * @param candidate the model output to evaluate; must not be null
     * @return immutable list of [system, user] messages
     */
    public static List<Message> render(String rubric, String candidate) {
        String user = "RUBRIC:\n" + rubric.strip()
                + "\n\nCANDIDATE:\n" + candidate.strip()
                + "\n\nRespond with the JSON object now.";
        return List.of(new Message.System(SYSTEM), new Message.User(user));
    }

    /**
     * Builds the conversation for a checklist judge call.
     *
     * @param checks    ordered list of binary check statements; must not be null or empty
     * @param candidate the model output to evaluate; must not be null
     * @return immutable list of [system, user] messages
     */
    public static List<Message> renderChecklist(List<String> checks, String candidate) {
        var sb = new StringBuilder("CHECKS:\n");
        for (int i = 0; i < checks.size(); i++) {
            sb.append(i + 1).append(". ").append(checks.get(i).strip()).append('\n');
        }
        sb.append("\nCANDIDATE:\n").append(candidate.strip());
        sb.append("\n\nRespond with the JSON object now.");
        return List.of(new Message.System(CHECKLIST_SYSTEM), new Message.User(sb.toString()));
    }

    /**
     * Returns the first balanced {@code {...}} JSON object substring found in {@code raw},
     * or {@code null} if none exists.
     *
     * <p>Handles nested braces and braces inside JSON string literals (including escaped
     * quotes), so prose-wrapped or markdown-fenced judge replies are tolerated. Reasoning
     * channels of the form {@code <think>...</think>} (and the unclosed-but-token-limited
     * {@code <think>...} with no closing tag) are stripped <i>before</i> extraction so JSON
     * the judge sketches inside its scratchpad doesn't get parsed as the verdict.</p>
     *
     * @param raw raw string from the judge model; may be null
     * @return extracted JSON substring, or null
     */
    public static String extractJsonBlock(String raw) {
        if (raw == null) return null;
        String cleaned = stripReasoningChannels(raw);
        int start = cleaned.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return cleaned.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * Removes reasoning-channel markup so a judge's scratch JSON inside a
     * {@code <think>} block is not mistaken for the verdict.
     *
     * <p>Handles three shapes:</p>
     * <ul>
     *   <li>Closed: {@code <think>...stuff...</think>tail} → {@code tail}.</li>
     *   <li>Multiple closed blocks: each removed.</li>
     *   <li>Unclosed (judge token-starved mid-thought): {@code <think>only thinking}
     *       → empty string. Caller surfaces the resulting "no JSON block" rationale.</li>
     * </ul>
     *
     * <p>Tag matching is case-insensitive and tolerant of whitespace.</p>
     */
    static String stripReasoningChannels(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String s = raw;
        // Remove all closed <think>...</think> blocks (non-greedy, dot-matches-newline).
        s = s.replaceAll("(?is)<think\\b[^>]*>.*?</think\\s*>", "");
        // Strip a trailing unclosed <think>... with no matching close tag.
        int openIdx = indexOfIgnoreCase(s, "<think");
        if (openIdx >= 0 && indexOfIgnoreCase(s, "</think", openIdx) < 0) {
            s = s.substring(0, openIdx);
        }
        return s;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return indexOfIgnoreCase(haystack, needle, 0);
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int n = haystack.length(), m = needle.length();
        for (int i = from; i + m <= n; i++) {
            if (haystack.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }
}
