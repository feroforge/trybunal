package org.trybunal.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.spi.ModelHarness;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * A {@link ModelHarness} decorator that runs the ReAct loop.
 *
 * <p>For each invocation:
 * <ol>
 *   <li>Inject the registered tools into {@code params.tools()} (merged with any tools the caller
 *       already supplied — caller's tools win on name collision).</li>
 *   <li>Delegate to the wrapped harness.</li>
 *   <li>If the assistant reply has no tool calls, return it.</li>
 *   <li>Otherwise dispatch each tool call (in parallel on the supplied virtual-thread executor),
 *       append the original assistant turn and one {@link Message.Tool} per result, and loop.</li>
 *   <li>Cap at {@code maxIterations}. On overflow, return the last assistant turn with finishReason
 *       {@code "tool-iteration-cap"} and a synthesized note in content.</li>
 * </ol>
 *
 * <p>Tool exceptions are converted to {@code ToolResult.error(...)} so the model can recover.
 * A tool that returns {@code isError=true} is logged at WARN with MDC {@code tool=<name>}.</p>
 */
public final class ToolCallingHarness implements ModelHarness {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingHarness.class);

    private final ModelHarness delegate;
    private final Map<String, Tool> toolsByName;
    private final int maxIterations;
    private final ExecutorService executor;

    /**
     * @param delegate      base harness; never null
     * @param tools         tools to advertise+dispatch; defensively copied; names must be unique
     * @param maxIterations &gt;= 1; recommended 8
     * @param executor      virtual-thread executor used to fan out tool calls; never null
     */
    public ToolCallingHarness(ModelHarness delegate, List<Tool> tools, int maxIterations,
                              ExecutorService executor) {
        if (delegate == null) throw new IllegalArgumentException("delegate required");
        if (tools == null) throw new IllegalArgumentException("tools required");
        if (maxIterations < 1) throw new IllegalArgumentException("maxIterations must be >= 1");
        if (executor == null) throw new IllegalArgumentException("executor required");

        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : tools) {
            String name = t.spec().name();
            if (byName.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate tool name: " + name);
            }
            byName.put(name, t);
        }

        this.delegate = delegate;
        this.toolsByName = Map.copyOf(byName);
        this.maxIterations = maxIterations;
        this.executor = executor;
    }

    @Override
    public InvocationResult run(List<Message> conversation, ModelId modelId, GenerationParams params) {
        GenerationParams mergedParams = mergeTools(params);
        List<Message> conv = new ArrayList<>(conversation);

        InvocationResult lastResult = null;
        for (int iter = 1; iter <= maxIterations; iter++) {
            MDC.put("iteration", String.valueOf(iter));
            try {
                lastResult = delegate.run(conv, modelId, mergedParams);
                List<ToolCall> toolCalls = lastResult.reply().toolCalls();
                log.debug("iter={} toolCalls={}", iter, toolCalls.size());

                if (toolCalls.isEmpty()) {
                    return lastResult;
                }

                conv.add(lastResult.reply());
                List<Message.Tool> toolResults = dispatchAll(toolCalls);
                conv.addAll(toolResults);
            } finally {
                MDC.remove("iteration");
            }
        }

        // iteration cap reached
        InvocationMetadata capMeta = new InvocationMetadata(
                lastResult.metadata().modelId(),
                lastResult.metadata().startedAt(),
                lastResult.metadata().latency(),
                lastResult.metadata().promptTokens(),
                lastResult.metadata().completionTokens(),
                lastResult.metadata().toolCalls(),
                "tool-iteration-cap",
                lastResult.metadata().providerExtras()
        );
        Message.Assistant capReply = new Message.Assistant(
                "[tool iteration cap reached]\n" + lastResult.reply().content(),
                lastResult.reply().toolCalls()
        );
        return new InvocationResult(capReply, capMeta);
    }

    private GenerationParams mergeTools(GenerationParams params) {
        Map<String, ToolSpec> merged = new LinkedHashMap<>();
        for (Tool t : toolsByName.values()) {
            merged.put(t.spec().name(), t.spec());
        }
        // caller's tools win on name collision
        for (ToolSpec ts : params.tools()) {
            merged.put(ts.name(), ts);
        }
        return params.withTools(List.copyOf(merged.values()));
    }

    private List<Message.Tool> dispatchAll(List<ToolCall> toolCalls) {
        List<Future<Message.Tool>> futures = toolCalls.stream()
                .map(tc -> executor.submit(() -> dispatchOne(tc)))
                .toList();
        List<Message.Tool> results = new ArrayList<>(futures.size());
        for (Future<Message.Tool> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolDispatchException("Tool dispatch interrupted", e);
            } catch (ExecutionException e) {
                // dispatchOne catches all exceptions internally; this shouldn't happen
                throw new ToolDispatchException("Unexpected tool dispatch failure", e.getCause());
            }
        }
        return results;
    }

    private Message.Tool dispatchOne(ToolCall tc) {
        MDC.put("tool", tc.toolName());
        try {
            Tool tool = toolsByName.get(tc.toolName());
            if (tool == null) {
                String available = String.join(", ", toolsByName.keySet());
                log.warn("Unknown tool '{}'; available: [{}]", tc.toolName(), available);
                return new Message.Tool(tc.id(),
                        "error: unknown tool '" + tc.toolName() + "'; available: [" + available + "]");
            }
            ToolResult result;
            try {
                result = tool.invoke(tc.arguments());
            } catch (Exception e) {
                log.warn("Tool '{}' threw exception", tc.toolName(), e);
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                return new Message.Tool(tc.id(), ToolResult.error(msg).content());
            }
            if (result.isError()) {
                log.warn("Tool '{}' returned error: {}", tc.toolName(), result.content());
            }
            return new Message.Tool(tc.id(), result.content());
        } finally {
            MDC.remove("tool");
        }
    }
}
