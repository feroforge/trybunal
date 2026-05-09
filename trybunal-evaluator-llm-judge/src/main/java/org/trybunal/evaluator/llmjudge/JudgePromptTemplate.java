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
     * Returns the first balanced {@code {...}} JSON object substring found in {@code raw},
     * or {@code null} if none exists.
     *
     * <p>Handles nested braces and braces inside JSON string literals (including escaped
     * quotes), so prose-wrapped or markdown-fenced judge replies are tolerated.</p>
     *
     * @param raw raw string from the judge model; may be null
     * @return extracted JSON substring, or null
     */
    public static String extractJsonBlock(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }
}
