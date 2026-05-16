package org.trybunal.examples.probe;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.provider.ollama.OllamaProvider;
import org.trybunal.tool.mocks.MockFixtures;

/**
 * One-shot probe that, for each Ollama model in
 * {@code -Dtrybunal.models=...}, binary-searches the largest user-message
 * size (in characters) the daemon accepts before returning:
 *
 * <ol>
 *   <li>the empty-placeholder response described in
 *       {@code examples/research-agent/TUNING-NOTES.md} round 8,</li>
 *   <li>a non-2xx HTTP status (surfaces as a {@link RuntimeException}
 *       from {@link OllamaProvider#invoke}), or</li>
 *   <li>a 60-second wall-clock timeout per attempt.</li>
 * </ol>
 *
 * <p>Prompt content comes from {@link MockFixtures#fetchedText} so two
 * runs against the same daemon + same {@code num_ctx} produce identical
 * numbers. No network calls except to the Ollama daemon.</p>
 *
 * <p>Writes {@code build/context-limits.md} with one Markdown row per
 * probed model on completion.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ./gradlew :examples:probe-context-limit:run \
 *     -Dtrybunal.models=gemma4:26b,gpt-oss:20b
 * }</pre>
 */
public final class ProbeContextLimit {

    private static final String DEFAULT_MODELS =
            "gemma4:26b,gpt-oss:20b,mistral-small:24b,llama3.1:8b";

    /** Wall-clock budget per single Ollama invocation. */
    private static final Duration PER_CALL_TIMEOUT = Duration.ofSeconds(60);

    /** Total wall-clock budget per model. */
    private static final Duration PER_MODEL_BUDGET = Duration.ofMinutes(5);

    /** num_ctx the probe explicitly sets so the ceiling is reproducible. */
    private static final int PROBE_NUM_CTX = 4096;

    /** Smallest size we ever probe — every model should comfortably handle this. */
    private static final int LOW_BOUND_CHARS = 200;

    /**
     * Highest size we'll attempt before giving up the binary search. Picked
     * large enough to overflow any 4 096-token window: 4 chars/token × 4 096
     * ≈ 16 384 chars, so 64 000 leaves a comfortable margin.
     */
    private static final int HIGH_BOUND_CHARS = 64_000;

    /** Stop the binary search once we've localised the ceiling within this many chars. */
    private static final int BISECTION_GRANULARITY = 500;

    /** Deterministic URL the prompt fixture is drawn from. */
    private static final URI FIXTURE_URL = URI.create("https://www.sec.gov/Archives/edgar/data/320193/aapl-10k.htm");

    public static void main(String[] args) throws Exception {
        String modelsProp = System.getProperty("trybunal.models", DEFAULT_MODELS);
        List<String> models = Arrays.stream(modelsProp.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (models.isEmpty()) {
            System.err.println("no models configured; pass -Dtrybunal.models=name[,name]");
            System.exit(2);
        }

        OllamaProvider provider = new OllamaProvider();
        List<ContextProbeReport> reports = new ArrayList<>();

        for (String modelName : models) {
            System.out.printf("Probing %s …%n", modelName);
            ContextProbeReport row = probeOne(provider, modelName);
            reports.add(row);
            System.out.printf("%-22s safe ≤ %,d chars  (~%,d tokens)  ceiling=%s  num_ctx=%d%n",
                    row.model(),
                    row.safeChars(),
                    row.approxTokens(),
                    row.ceilingChars() < 0 ? "—" : String.format("%,d", row.ceilingChars()),
                    row.numCtx());
        }

        Path out = Path.of("build", "context-limits.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, ContextProbeReport.toMarkdownTable(reports));
        System.out.printf("Wrote %s%n", out.toAbsolutePath());
    }

    /**
     * Binary-search the largest user-message size {@code modelName} accepts
     * before failing. Aborts (returning the best-so-far row) if the model
     * exceeds {@link #PER_MODEL_BUDGET} wall-clock.
     */
    static ContextProbeReport probeOne(OllamaProvider provider, String modelName) {
        ModelId modelId = new ModelId("ollama", modelName);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("num_ctx", PROBE_NUM_CTX);
        extras.put("think", false);
        GenerationParams params = new GenerationParams(
                0.0, 64, null, 1L, extras, List.of());

        long deadline = System.nanoTime() + PER_MODEL_BUDGET.toNanos();

        int lo = LOW_BOUND_CHARS;
        int hi = HIGH_BOUND_CHARS;
        int safe = 0;
        int ceiling = -1;
        String failureMode = "none";

        // Find an initial failing high bound by exponential probe so we don't
        // waste budget bisecting an interval the model fits inside trivially.
        int probe = lo;
        while (probe <= hi && System.nanoTime() < deadline) {
            AttemptResult r = attempt(provider, modelId, params, probe);
            if (r.success()) {
                safe = probe;
                probe = Math.min(hi, probe * 2);
                if (safe == hi) break; // model swallowed the whole high bound
            } else {
                ceiling = probe;
                failureMode = r.failureMode();
                break;
            }
        }

        if (ceiling < 0) {
            // Never failed inside [LOW_BOUND, HIGH_BOUND]; return early.
            return new ContextProbeReport(
                    modelName, PROBE_NUM_CTX, safe, safe / 4, -1, failureMode);
        }

        // Standard bisection between safe and ceiling.
        lo = safe;
        hi = ceiling;
        while (hi - lo > BISECTION_GRANULARITY && System.nanoTime() < deadline) {
            int mid = lo + (hi - lo) / 2;
            AttemptResult r = attempt(provider, modelId, params, mid);
            if (r.success()) {
                lo = mid;
                safe = mid;
            } else {
                hi = mid;
                ceiling = mid;
                failureMode = r.failureMode();
            }
        }

        return new ContextProbeReport(
                modelName, PROBE_NUM_CTX, safe, safe / 4, ceiling, failureMode);
    }

    /**
     * Sends one probe message of {@code chars} characters drawn from
     * {@link MockFixtures#fetchedText} and reports whether the call returned
     * a usable reply. Bounded by {@link #PER_CALL_TIMEOUT}.
     */
    static AttemptResult attempt(OllamaProvider provider, ModelId modelId,
                                 GenerationParams params, int chars) {
        String body = buildPrompt(chars);
        List<Message> conv = List.of(
                new Message.System("You are a probe. Reply with the single word OK."),
                new Message.User(body)
        );
        var oneShot = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<InvocationResult> future = oneShot.submit(
                    () -> provider.invoke(conv, modelId, params));
            InvocationResult result;
            try {
                result = future.get(PER_CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                return new AttemptResult(false, "timeout");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
                if (msg.contains("ollama returned")) return new AttemptResult(false, "http-error");
                return new AttemptResult(false, "transport-error");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new AttemptResult(false, "interrupted");
            }
            // Provider already retried the empty-placeholder case up to its
            // internal limit; if it still came back empty, that's the ceiling.
            var reply = result.reply();
            var meta = result.metadata();
            boolean empty = (reply.content() == null || reply.content().isEmpty())
                    && reply.toolCalls().isEmpty()
                    && meta.promptTokens() == null
                    && meta.finishReason() == null;
            if (empty) return new AttemptResult(false, "empty-placeholder");
            return new AttemptResult(true, "ok");
        } finally {
            oneShot.shutdownNow();
        }
    }

    /** Build a user prompt of exactly {@code chars} characters from MockFixtures. */
    static String buildPrompt(int chars) {
        if (chars <= 0) return "";
        String base = MockFixtures.fetchedText(FIXTURE_URL, Integer.MAX_VALUE);
        if (base.isEmpty()) return new String(new char[chars]).replace('\0', '.');
        StringBuilder sb = new StringBuilder(chars + base.length());
        sb.append("Summarise the following document in one word.\n\n");
        while (sb.length() < chars) {
            sb.append(base).append("\n");
        }
        sb.setLength(chars);
        return sb.toString();
    }

    /** Internal result of one probe attempt. */
    record AttemptResult(boolean success, String failureMode) {}
}
