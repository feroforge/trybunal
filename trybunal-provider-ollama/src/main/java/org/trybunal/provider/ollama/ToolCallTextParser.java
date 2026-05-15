package org.trybunal.provider.ollama;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.model.ToolCall;

/**
 * Pure, stateless parser for the model-native tool-call text channels that
 * some Ollama models emit in {@code message.content} instead of the
 * structured {@code message.tool_calls} array.
 *
 * <p>Recognises two prefixes when they appear at the first non-blank
 * character of the content:</p>
 *
 * <ul>
 *   <li><b>Llama 3.x</b> — {@code <|python_tag|>name(arg=value, ...)}, one or
 *       more calls separated by newlines.</li>
 *   <li><b>Mistral / Mistral-Small</b> — {@code [TOOL_CALLS]} followed either
 *       by the same {@code name(arg=value, ...)} grammar or by a JSON array
 *       of {@code {"name":"...","arguments":{...}}} objects.</li>
 * </ul>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #parse(String)} is pure, thread-safe, and never throws — even
 *       for malformed or partial input.</li>
 *   <li>When no recognised marker is present at the first non-blank
 *       character, returns a {@link ParseResult} with an empty {@code calls}
 *       list and {@code remainingContent} equal to the input.</li>
 *   <li>When a marker is present but the body fails to parse, returns the
 *       same "empty + original content" result and logs one WARN line. The
 *       caller then surfaces the raw text to the user rather than crashing
 *       the harness.</li>
 *   <li>When at least one call is parsed, {@code remainingContent} contains
 *       whatever text follows the parsed prefix (empty when nothing trails).</li>
 * </ul>
 */
public final class ToolCallTextParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTextParser.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PYTHON_TAG_MARKER = "<|python_tag|>";
    private static final String TOOL_CALLS_MARKER = "[TOOL_CALLS]";

    private ToolCallTextParser() {}

    /**
     * Outcome of one parse attempt over a content blob.
     *
     * @param calls            parsed tool calls in source order; never null,
     *                         defensively copied, empty when no marker was
     *                         present or the body failed to parse
     * @param remainingContent content left over after the parsed prefix was
     *                         stripped; equals the original input when no
     *                         calls were extracted
     */
    public record ParseResult(List<ToolCall> calls, String remainingContent) {
        public ParseResult {
            calls = List.copyOf(calls);
            if (remainingContent == null) remainingContent = "";
        }
    }

    /**
     * Parses tool-call markers out of {@code content}. See the class-level
     * javadoc for the recognised grammars.
     *
     * @param content raw {@code message.content} from the provider; null is
     *                tolerated and treated as empty
     * @return a non-null {@link ParseResult}; never throws
     */
    public static ParseResult parse(String content) {
        if (content == null) return new ParseResult(List.of(), "");
        int leading = 0;
        while (leading < content.length() && Character.isWhitespace(content.charAt(leading))) leading++;
        if (leading >= content.length()) return new ParseResult(List.of(), content);

        String tail = content.substring(leading);
        if (tail.startsWith(PYTHON_TAG_MARKER)) {
            return parseAfterMarker(content, leading + PYTHON_TAG_MARKER.length(),
                    PYTHON_TAG_MARKER, false);
        }
        if (tail.startsWith(TOOL_CALLS_MARKER)) {
            return parseAfterMarker(content, leading + TOOL_CALLS_MARKER.length(),
                    TOOL_CALLS_MARKER, true);
        }
        return new ParseResult(List.of(), content);
    }

    private static ParseResult parseAfterMarker(String content, int bodyStart,
                                                String marker, boolean allowJsonArray) {
        try {
            int cursor = skipWhitespace(content, bodyStart);
            if (allowJsonArray && cursor < content.length() && content.charAt(cursor) == '[') {
                ParseResult json = tryParseJsonArray(content, cursor);
                if (json != null) return json;
            }
            List<ToolCall> calls = new ArrayList<>();
            while (cursor < content.length()) {
                cursor = skipWhitespace(content, cursor);
                if (cursor >= content.length()) break;
                int next = parseCallStatement(content, cursor, calls);
                if (next < 0) break;
                cursor = next;
            }
            if (calls.isEmpty()) {
                warnMalformed(marker, content, bodyStart);
                return new ParseResult(List.of(), content);
            }
            String remaining = cursor >= content.length() ? "" : content.substring(cursor);
            return new ParseResult(calls, remaining);
        } catch (RuntimeException e) {
            warnMalformed(marker, content, bodyStart);
            return new ParseResult(List.of(), content);
        }
    }

    private static ParseResult tryParseJsonArray(String content, int start) {
        try (JsonParser p = JSON.getFactory().createParser(content.substring(start))) {
            JsonNode arr = JSON.readTree(p);
            if (arr == null || !arr.isArray()) return null;
            List<ToolCall> calls = new ArrayList<>(arr.size());
            for (JsonNode node : arr) {
                String name = node.path("name").asText(null);
                if (name == null || name.isBlank()) return null;
                JsonNode argsNode = node.path("arguments");
                Map<String, Object> args = argsNode.isObject()
                        ? JSON.convertValue(argsNode, new TypeReference<Map<String, Object>>() {})
                        : Map.of();
                String id = "call_" + Integer.toHexString(System.identityHashCode(node));
                calls.add(new ToolCall(id, name, args));
            }
            long consumed = p.currentLocation().getCharOffset();
            int remainingStart = start + (int) consumed;
            String remaining = remainingStart >= content.length()
                    ? ""
                    : content.substring(remainingStart);
            return new ParseResult(calls, remaining);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseCallStatement(String s, int start, List<ToolCall> out) {
        int i = start;
        int nameStart = i;
        while (i < s.length() && isIdentChar(s.charAt(i))) i++;
        if (i == nameStart) return -1;
        String name = s.substring(nameStart, i);
        i = skipWhitespace(s, i);
        if (i >= s.length() || s.charAt(i) != '(') return -1;
        i++; // consume '('
        Map<String, Object> args = new LinkedHashMap<>();
        int positional = 0;
        while (true) {
            i = skipWhitespace(s, i);
            if (i >= s.length()) return -1;
            if (s.charAt(i) == ')') {
                i++;
                String id = "call_" + Integer.toHexString(System.identityHashCode(args));
                out.add(new ToolCall(id, name, args));
                return i;
            }
            ArgParse parsed = parseArg(s, i, args, positional);
            if (parsed == null) return -1;
            if (parsed.positional) positional++;
            i = parsed.end;
            i = skipWhitespace(s, i);
            if (i >= s.length()) return -1;
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c != ')') {
                return -1;
            }
        }
    }

    private static String positionalKey(int index) {
        return index == 0 ? "query" : "arg" + index;
    }

    private record ArgParse(int end, boolean positional) {}

    /**
     * Parses one argument and inserts it into {@code args}. Returns the
     * position immediately after the parsed argument plus whether the
     * argument was positional (so the caller can advance its positional
     * counter), or null if no argument could be parsed.
     */
    private static ArgParse parseArg(String s, int start, Map<String, Object> args, int positional) {
        int i = start;
        int idStart = i;
        while (i < s.length() && isIdentChar(s.charAt(i))) i++;
        if (i > idStart) {
            int afterName = skipWhitespace(s, i);
            if (afterName < s.length() && s.charAt(afterName) == '=') {
                String name = s.substring(idStart, i);
                int valStart = skipWhitespace(s, afterName + 1);
                long packed = parseValue(s, valStart);
                if (packed < 0) return null;
                args.put(name, lastValue.get());
                return new ArgParse(unpackEnd(packed), false);
            }
            // Not name=value; rewind and try as a positional value.
            i = idStart;
        }
        long packed = parseValue(s, i);
        if (packed < 0) return null;
        args.put(positionalKey(positional), lastValue.get());
        return new ArgParse(unpackEnd(packed), true);
    }

    // Thread-local channel for the parsed value associated with a parseValue
    // call. Avoids allocating a value-holder record per arg. Safe because the
    // parser is single-threaded per invocation.
    private static final ThreadLocal<Object> lastValue = new ThreadLocal<>();

    private static int unpackEnd(long packed) {
        return (int) packed;
    }

    /**
     * Reads a scalar literal at {@code start}. On success, stows the parsed
     * Java value into {@link #lastValue} and returns the end index packed
     * into a non-negative long. Returns -1 if no literal can be parsed.
     */
    private static long parseValue(String s, int start) {
        if (start >= s.length()) return -1;
        char c = s.charAt(start);
        if (c == '"' || c == '\'') return parseQuoted(s, start, c);

        int i = start;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        int numStart = i;
        boolean sawDigit = false;
        boolean sawDot = false;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) { sawDigit = true; i++; }
            else if (ch == '.' && !sawDot) { sawDot = true; i++; }
            else break;
        }
        if (sawDigit) {
            String num = s.substring(start, i);
            try {
                Object v = sawDot ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
                lastValue.set(v);
                return i;
            } catch (NumberFormatException ignored) {
                // fall through to bare-word handling
            }
        }

        // Bare word: read until the next ',' or ')' at the current depth (we
        // don't allow nested parens in bare words — that's a structured
        // case and should have been routed through JSON).
        int w = start;
        while (w < s.length() && s.charAt(w) != ',' && s.charAt(w) != ')') w++;
        if (w == start) return -1;
        String word = s.substring(start, w).trim();
        if (word.isEmpty()) return -1;
        lastValue.set(word);
        return w;
    }

    private static long parseQuoted(String s, int start, char quote) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    default -> sb.append(next);
                }
                i += 2;
            } else if (c == quote) {
                lastValue.set(sb.toString());
                return i + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void warnMalformed(String marker, String content, int bodyStart) {
        int from = Math.min(bodyStart, content.length());
        String body = content.substring(from);
        String preview = body.length() > 80 ? body.substring(0, 80) : body;
        log.warn("tool-call text marker {} present but body failed to parse: {}", marker, preview);
    }
}
