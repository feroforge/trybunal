package org.trybunal.evaluator.llmjudge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final ObjectMapper PARSER = new ObjectMapper();

    private static final Pattern THINK_BLOCK =
            Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");

    private static final Pattern FENCE_JSON =
            Pattern.compile("(?is)```\\s*json\\s*\\R?(.*?)```");

    private static final Pattern FENCE_PLAIN =
            Pattern.compile("(?is)```\\s*\\R?(.*?)```");

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
     * Extracts a JSON document from a judge model's raw reply, tolerating the
     * many shapes models actually emit. The first candidate that parses as
     * valid JSON wins; precedence is:
     *
     * <ol>
     *   <li>Trimmed input is itself a JSON document.</li>
     *   <li>After stripping {@code <think>...</think>} blocks, the trimmed
     *       remainder is itself a JSON document.</li>
     *   <li>A fenced {@code ```json ... ```} block.</li>
     *   <li>An unlabelled fenced {@code ``` ... ```} block whose payload
     *       starts with {@code {} or {@code [}.</li>
     *   <li>The last balanced {@code {...}} or {@code [...]} substring whose
     *       opening token is preceded by whitespace (or start of input).</li>
     * </ol>
     *
     * @param raw raw string from the judge model; may be null
     * @return extracted JSON substring, or null if none of the strategies match
     */
    public static String extractJsonBlock(String raw) {
        if (raw == null) return null;

        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;

        if (startsWithJsonOpener(trimmed) && parses(trimmed)) {
            return trimmed;
        }

        String stripped = THINK_BLOCK.matcher(raw).replaceAll("");
        String strippedTrimmed = stripped.strip();
        if (!strippedTrimmed.isEmpty()
                && startsWithJsonOpener(strippedTrimmed)
                && parses(strippedTrimmed)) {
            return strippedTrimmed;
        }

        Matcher mJson = FENCE_JSON.matcher(stripped);
        while (mJson.find()) {
            String inner = mJson.group(1).strip();
            if (parses(inner)) return inner;
        }

        Matcher mPlain = FENCE_PLAIN.matcher(stripped);
        while (mPlain.find()) {
            String inner = mPlain.group(1).strip();
            if (startsWithJsonOpener(inner) && parses(inner)) return inner;
        }

        String last = findLastBalancedJson(stripped);
        if (last != null) return last;

        return null;
    }

    private static boolean startsWithJsonOpener(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    private static boolean parses(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            PARSER.readTree(s);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Walks {@code s} left-to-right collecting every balanced top-level
     * {@code {...}} or {@code [...]} substring whose opening token sits at the
     * start of input or is preceded by a whitespace character. Returns the
     * rightmost such substring that parses as JSON, or null.
     *
     * <p>The walker is string-literal aware so braces inside JSON string
     * values do not perturb the depth counter.</p>
     */
    private static String findLastBalancedJson(String s) {
        String lastValid = null;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if ((c == '{' || c == '[') && precededByWhitespace(s, i)) {
                int end = scanBalanced(s, i);
                if (end > i) {
                    String candidate = s.substring(i, end + 1);
                    if (parses(candidate)) {
                        lastValid = candidate;
                    }
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }
        return lastValid;
    }

    private static boolean precededByWhitespace(String s, int idx) {
        if (idx == 0) return true;
        return Character.isWhitespace(s.charAt(idx - 1));
    }

    /**
     * Given an opening {@code {} or {@code [} at {@code start}, returns the
     * index of its matching close, or -1 if unbalanced. String-literal aware.
     */
    private static int scanBalanced(String s, int start) {
        char open = s.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
