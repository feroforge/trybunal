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
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelProvider;

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

        Instant startedAt = Instant.now();
        HttpResponse<String> resp;
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

        InvocationResult result = decodeResponse(root, modelId, startedAt);
        var meta = result.metadata();
        log.debug("ollama call done finish={} promptTok={} complTok={} thinking={}",
                meta.finishReason(), meta.promptTokens(), meta.completionTokens(),
                meta.providerExtras().containsKey("thinking"));
        return result;
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
    static InvocationResult decodeResponse(JsonNode root, ModelId modelId, Instant startedAt) {
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

        Map<String, Object> extras = Map.of();
        if (!thinking.isBlank()) {
            // LinkedHashMap so future extras retain insertion order in logs/snapshots.
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("thinking", thinking);
            extras = m;
        }

        var metadata = new InvocationMetadata(
                modelId, startedAt, providerDuration,
                promptTokens, completionTokens, List.of(), finishReason, extras);

        return new InvocationResult(Message.Assistant.of(content), metadata);
    }

    private static ArrayNode encodeMessages(List<Message> conversation) {
        ArrayNode arr = JSON.createArrayNode();
        for (Message m : conversation) {
            ObjectNode node = JSON.createObjectNode();
            node.put("role", roleOf(m));
            node.put("content", m.content());
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
}
