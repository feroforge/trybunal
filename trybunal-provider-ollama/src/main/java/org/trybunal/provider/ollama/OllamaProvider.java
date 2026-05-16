package org.trybunal.provider.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trybunal.api.model.ContextWindow;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.tool.ToolSpec;

/**
 * {@link ModelProvider} backed by a local Ollama daemon
 * (default host {@code http://localhost:11434}).
 *
 * <p>The host can be overridden via the {@code OLLAMA_HOST} environment
 * variable or by passing it to the constructor.</p>
 *
 * <p>Speaks to {@code POST /api/chat} with {@code stream=false} and maps the
 * response into Trybunal's domain types. Pure I/O — all timing, logging, and
 * tool dispatch live in the harness layer per the Trybunal Pattern.</p>
 */
public final class OllamaProvider implements ModelProvider {

    public static final String ID = "ollama";
    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Keys in {@link GenerationParams#providerExtras()} that Ollama's {@code /api/chat}
     * accepts at the <i>top level</i> of the request body rather than nested under
     * {@code options}. {@code "think"} is the most useful: setting it to {@code false}
     * tells reasoning models (gemma4, gpt-oss, …) to skip their thinking channel and
     * write the answer directly, dramatically cutting latency on structured-output cases.
     */
    private static final Set<String> TOP_LEVEL_OPTIONS = Set.of("think", "format", "keep_alive");

    /**
     * Ollama loads every model with {@code num_ctx=4096} by default,
     * regardless of the modelcard maximum. We surface this as the
     * effective {@link ContextWindow#numCtx()} ceiling whenever the
     * caller hasn't set {@code options.num_ctx} explicitly.
     */
    static final int DEFAULT_NUM_CTX = 4096;

    private final URI baseUri;
    private final HttpClient http;

    public OllamaProvider() {
        this(resolveDefaultHost());
    }

    public OllamaProvider(String baseUri) {
        if (baseUri == null || baseUri.isBlank())
            throw new IllegalArgumentException("baseUri required");
        this.baseUri = URI.create(baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String resolveDefaultHost() {
        String env = System.getenv("OLLAMA_HOST");
        return (env == null || env.isBlank()) ? "http://localhost:11434" : env;
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean supports(ModelId modelId) {
        return modelId != null && ID.equals(modelId.provider());
    }

    @Override
    public InvocationResult invoke(List<Message> conversation, ModelId modelId, GenerationParams params) {
        if (!supports(modelId))
            throw new IllegalArgumentException("unsupported model: " + modelId);

        ObjectNode body = JSON.createObjectNode();
        body.put("model", modelId.name());
        body.put("stream", false);
        body.set("messages", encodeMessages(conversation));
        body.set("options", encodeOptions(params));
        // Hoist Ollama-specific top-level flags (think, format, keep_alive) out of
        // providerExtras into the request body root.
        params.providerExtras().forEach((k, v) -> {
            if (TOP_LEVEL_OPTIONS.contains(k)) body.putPOJO(k, v);
        });
        if (!params.tools().isEmpty()) {
            body.set("tools", encodeTools(params.tools()));
        }

        HttpRequest req;
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("failed to encode request", e);
        }
        // Generous read timeout: with reasoning models (gemma4, phi4-reasoning,
        // gpt-oss) and a concurrent evaluateAll fan-out, the 12th queued
        // request can wait behind 11 others on the single-model Ollama
        // daemon. Five minutes is too tight for that pattern; 20 leaves
        // room for any single inference + queue depth without false-failing.
        req = HttpRequest.newBuilder(baseUri.resolve("/api/chat"))
                .timeout(Duration.ofMinutes(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        // Ollama occasionally returns an "empty placeholder" response —
        // {"model":"","done":false,"message":{"role":"","content":""}} —
        // for valid requests, especially during multi-tool ReAct loops on
        // gemma3-family models. The same request often succeeds on retry,
        // so we transparently retry up to RETRY_LIMIT times with a short
        // back-off before surfacing the failure.
        // Resolve the effective num_ctx for the ContextWindow snapshot.
        // Ollama silently loads every model at num_ctx=4096 unless the caller
        // hoisted an explicit value into providerExtras; we mirror that here.
        int effectiveNumCtx = resolveNumCtx(params);

        Instant startedAt = Instant.now();
        InvocationResult result = null;
        HttpResponse<String> resp = null;
        for (int attempt = 1; attempt <= RETRY_LIMIT; attempt++) {
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException("ollama transport error: " + e.getMessage(), e);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("ollama returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root;
            try {
                root = JSON.readTree(resp.body());
            } catch (Exception e) {
                throw new RuntimeException("failed to parse ollama response", e);
            }
            result = decodeResponse(root, modelId, startedAt, effectiveNumCtx);
            if (!isEmptyPlaceholder(result)) break;
            log.warn("ollama empty-placeholder response on attempt {}/{}; retrying after backoff",
                    attempt, RETRY_LIMIT);
            try { Thread.sleep(EMPTY_RETRY_BACKOFF_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        var meta = result.metadata();
        log.debug("ollama call done finish={} promptTok={} complTok={} thinking={}",
                meta.finishReason(), meta.promptTokens(), meta.completionTokens(),
                meta.providerExtras().containsKey("thinking"));
        if (isEmptyPlaceholder(result)) {
            log.warn("ollama returned empty placeholder after {} retries; req-len={} resp-len={} resp-body: {}",
                    RETRY_LIMIT, json.length(),
                    resp == null ? 0 : resp.body().length(),
                    resp == null ? "" : resp.body().substring(0, Math.min(resp.body().length(), 1000)));
        }
        return result;
    }

    /** Number of attempts (initial + retries) for the empty-placeholder workaround. */
    private static final int RETRY_LIMIT = 3;

    /** Back-off between empty-placeholder retries. Short — Ollama recovers fast. */
    private static final long EMPTY_RETRY_BACKOFF_MS = 500L;

    /**
     * Detects the empty-placeholder response Ollama sometimes returns instead
     * of an actual model output. Distinguished by null finish reason, empty
     * content, no tool calls, and null token counts — the Go zero-value of
     * the ChatResponse struct.
     */
    private static boolean isEmptyPlaceholder(InvocationResult result) {
        var meta = result.metadata();
        return meta.finishReason() == null
                && meta.promptTokens() == null
                && meta.completionTokens() == null
                && (result.reply().content() == null || result.reply().content().isEmpty())
                && result.reply().toolCalls().isEmpty();
    }

    /**
     * Decodes a parsed Ollama {@code /api/chat} response body into an
     * {@link InvocationResult}. Extracted from {@link #invoke} so transport
     * concerns and JSON shape concerns can be tested independently.
     *
     * <p>Captures {@code message.thinking} (when non-blank) under
     * {@code providerExtras["thinking"]}. Models that route reasoning to a
     * separate channel (gemma, gpt-oss with harmony, …) otherwise lose it
     * entirely — and the thinking is exactly what's useful when a content
     * field comes back empty.</p>
     */
    /**
     * Backwards-compatible overload that assumes the Ollama default
     * {@code num_ctx=4096}. Existing callers (and tests) that don't carry
     * a request-side num_ctx through to the decoder route here.
     */
    static InvocationResult decodeResponse(JsonNode root, ModelId modelId, Instant startedAt) {
        return decodeResponse(root, modelId, startedAt, DEFAULT_NUM_CTX);
    }

    static InvocationResult decodeResponse(JsonNode root, ModelId modelId, Instant startedAt, int numCtx) {
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");
        String thinking = message.path("thinking").asText("");
        String finishReason = root.path("done_reason").asText(null);
        Integer promptTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asInt() : null;
        Integer completionTokens = root.has("eval_count") ? root.get("eval_count").asInt() : null;

        // Provider reports its own duration; harness will overwrite with measured wall-clock.
        Duration providerDuration = root.has("total_duration")
                ? Duration.ofNanos(root.get("total_duration").asLong())
                : Duration.ZERO;

        List<ToolCall> toolCalls = decodeToolCalls(message);
        String toolCallOrigin = toolCalls.isEmpty() ? null : "structured";
        if (toolCalls.isEmpty() && !content.isBlank()) {
            var fallback = ToolCallTextParser.parse(content);
            if (!fallback.calls().isEmpty()) {
                toolCalls = fallback.calls();
                toolCallOrigin = detectTextOrigin(content);
                content = stripParsedPrefix(content, fallback.remainingContent());
            }
        }

        Map<String, Object> extras = Map.of();
        if (!thinking.isBlank() || toolCallOrigin != null) {
            // LinkedHashMap so future extras retain insertion order in logs/snapshots.
            Map<String, Object> m = new LinkedHashMap<>();
            if (!thinking.isBlank()) m.put("thinking", thinking);
            if (toolCallOrigin != null) m.put("tool_call_origin", toolCallOrigin);
            extras = m;
        }

        // Surface the configured context-window ceiling alongside the prompt
        // token count so harnesses can detect low headroom and (Task 03)
        // trigger compaction. When prompt_eval_count is absent (empty-
        // placeholder responses) we leave contextWindow null — there's no
        // promptTokens to anchor it to.
        ContextWindow contextWindow = (promptTokens != null && numCtx > 0)
                ? new ContextWindow(promptTokens, numCtx)
                : null;

        var metadata = new InvocationMetadata(
                modelId, startedAt, providerDuration,
                promptTokens, completionTokens, toolCalls, finishReason, extras,
                contextWindow);

        return new InvocationResult(new Message.Assistant(content, toolCalls), metadata);
    }

    private static ArrayNode encodeMessages(List<Message> conversation) {
        ArrayNode arr = JSON.createArrayNode();
        for (Message m : conversation) {
            ObjectNode node = JSON.createObjectNode();
            node.put("role", roleOf(m));
            node.put("content", m.content());
            // Ollama expects tool_calls echoed back on the assistant turn that
            // produced them. Without this, ReAct loops break on the SECOND
            // round-trip: Ollama sees `assistant("") → tool(result)` with no
            // call linking the two, treats the assistant turn as malformed,
            // and on some models returns an empty placeholder
            // {"done":false,"message":{"role":"","content":""}} that crashes
            // the rest of the loop. Affected: gemma4:26b on multi-tool runs.
            if (m instanceof Message.Assistant a && !a.toolCalls().isEmpty()) {
                ArrayNode tcArr = JSON.createArrayNode();
                for (ToolCall tc : a.toolCalls()) {
                    ObjectNode tcNode = JSON.createObjectNode();
                    if (tc.id() != null && !tc.id().isBlank()) {
                        tcNode.put("id", tc.id());
                    }
                    ObjectNode fn = JSON.createObjectNode();
                    fn.put("name", tc.toolName());
                    fn.set("arguments", JSON.valueToTree(tc.arguments()));
                    tcNode.set("function", fn);
                    tcArr.add(tcNode);
                }
                node.set("tool_calls", tcArr);
            }
            arr.add(node);
        }
        return arr;
    }

    private static String roleOf(Message m) {
        return switch (m) {
            case Message.System ignored -> "system";
            case Message.User ignored -> "user";
            case Message.Assistant ignored -> "assistant";
            case Message.Tool ignored -> "tool";
        };
    }

    /**
     * Picks the effective {@code num_ctx} for this call. If the caller hoisted
     * an explicit {@code num_ctx} into {@link GenerationParams#providerExtras()}
     * we honour it (parsing numeric strings defensively). Otherwise we fall
     * back to Ollama's default of {@value #DEFAULT_NUM_CTX} — the modelcard
     * maximum is not the loaded value and probing {@code /api/show} would lie
     * about the runtime ceiling.
     */
    private static int resolveNumCtx(GenerationParams params) {
        Object raw = params.providerExtras().get("num_ctx");
        if (raw == null) return DEFAULT_NUM_CTX;
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : DEFAULT_NUM_CTX;
        }
        if (raw instanceof CharSequence cs) {
            try {
                int v = Integer.parseInt(cs.toString().trim());
                return v > 0 ? v : DEFAULT_NUM_CTX;
            } catch (NumberFormatException ignored) {
                return DEFAULT_NUM_CTX;
            }
        }
        return DEFAULT_NUM_CTX;
    }

    private static ObjectNode encodeOptions(GenerationParams params) {
        ObjectNode opts = JSON.createObjectNode();
        if (params.temperature() != null) opts.put("temperature", params.temperature());
        if (params.maxTokens() != null) opts.put("num_predict", params.maxTokens());
        if (params.topP() != null) opts.put("top_p", params.topP());
        if (params.seed() != null) opts.put("seed", params.seed());
        // Top-level keys (think, format, keep_alive) are hoisted to the request body in invoke();
        // skip them here to avoid duplicate serialization under options.
        params.providerExtras().forEach((k, v) -> {
            if (!TOP_LEVEL_OPTIONS.contains(k)) opts.putPOJO(k, v);
        });
        return opts;
    }

    static ArrayNode encodeTools(List<ToolSpec> tools) {
        ArrayNode arr = JSON.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode entry = JSON.createObjectNode();
            entry.put("type", "function");
            ObjectNode fn = JSON.createObjectNode();
            fn.put("name", spec.name());
            fn.put("description", spec.description());
            fn.set("parameters", JSON.valueToTree(spec.jsonSchema()));
            entry.set("function", fn);
            arr.add(entry);
        }
        return arr;
    }

    /**
     * Identifies which text-channel marker started {@code content}. Used to
     * stamp {@code providerExtras["tool_call_origin"]} when the text fallback
     * extracts calls. Assumes a marker is present at the first non-blank
     * character — the fallback is only invoked once that is established.
     */
    private static String detectTextOrigin(String content) {
        int i = 0;
        while (i < content.length() && Character.isWhitespace(content.charAt(i))) i++;
        String tail = content.substring(i);
        if (tail.startsWith("<|python_tag|>")) return "text:python_tag";
        if (tail.startsWith("[TOOL_CALLS]")) {
            int j = i + "[TOOL_CALLS]".length();
            while (j < content.length() && Character.isWhitespace(content.charAt(j))) j++;
            if (j < content.length() && content.charAt(j) == '[') return "text:tool_calls_json";
            return "text:tool_calls";
        }
        return "text:unknown";
    }

    /**
     * Returns the trimmed remaining content from a successful parse. The
     * parser hands us the substring left over after the marker + parsed
     * calls; this strips a leading newline / whitespace so downstream
     * renderers don't show a blank line where the tool-call text used to be.
     */
    private static String stripParsedPrefix(String original, String remaining) {
        if (remaining == null || remaining.isEmpty()) return "";
        int i = 0;
        while (i < remaining.length() && Character.isWhitespace(remaining.charAt(i))) i++;
        return remaining.substring(i);
    }

    static List<ToolCall> decodeToolCalls(JsonNode message) {
        JsonNode toolCallsNode = message.path("tool_calls");
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) return List.of();
        var result = new java.util.ArrayList<ToolCall>(toolCallsNode.size());
        for (JsonNode node : toolCallsNode) {
            JsonNode fn = node.path("function");
            String name = fn.path("name").asText(null);
            if (name == null || name.isBlank())
                throw new IllegalArgumentException("tool_call entry missing function.name");
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank())
                id = "call_" + Integer.toHexString(System.identityHashCode(node));
            JsonNode argsNode = fn.path("arguments");
            Map<String, Object> args;
            if (argsNode.isObject()) {
                args = JSON.convertValue(argsNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } else {
                args = Map.of();
            }
            result.add(new ToolCall(id, name, args));
        }
        return List.copyOf(result);
    }
}
