package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

class OrchestratorMaxIterationsTest {

    private static final ModelId MODEL = new ModelId("stub", "m");

    private static InvocationResult toolCallReply(ModelId model, ToolCall tc) {
        var meta = new InvocationMetadata(model, Instant.now(), Duration.ofMillis(1),
                null, null, List.of(tc), "tool_use");
        return new InvocationResult(new Message.Assistant("", List.of(tc)), meta);
    }

    /** Provider that always replies with the same single tool call. */
    private static ModelProvider alwaysCallsTool(String id, AtomicInteger callCount) {
        return new ModelProvider() {
            @Override public String id() { return id; }
            @Override public boolean supports(ModelId m) { return id.equals(m.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                callCount.incrementAndGet();
                return toolCallReply(m, new ToolCall("id-" + callCount.get(), "noop", Map.of()));
            }
        };
    }

    private static Tool noopTool(AtomicInteger invocations) {
        var spec = new ToolSpec("noop", "no-op tool", Map.of());
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolResult invoke(Map<String, Object> args) {
                invocations.incrementAndGet();
                return ToolResult.ok("done");
            }
        };
    }

    @Test
    void defaultCapMatchesConstant() {
        var provider = new ModelProvider() {
            @Override public String id() { return "stub"; }
            @Override public boolean supports(ModelId m) { return "stub".equals(m.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                var meta = new InvocationMetadata(m, Instant.now(), Duration.ofMillis(1),
                        null, null, List.of(), "stop");
                return new InvocationResult(Message.Assistant.of("ok"), meta);
            }
        };
        try (var orch = Orchestrator.of(List.of(provider), List.of(), List.of())) {
            assertEquals(Orchestrator.DEFAULT_MAX_TOOL_ITERATIONS, orch.maxToolIterations());
            assertEquals(8, Orchestrator.DEFAULT_MAX_TOOL_ITERATIONS);
        }
    }

    @Test
    void propertyConstants() {
        assertEquals("trybunal.maxToolIterations", Orchestrator.MAX_ITER_PROPERTY);
    }

    @Test
    void explicitCapPropagatesAndTerminatesAfterExactlyNRounds() {
        var providerCalls = new AtomicInteger();
        var toolInvocations = new AtomicInteger();
        var provider = alwaysCallsTool("stub", providerCalls);
        var tool = noopTool(toolInvocations);
        var session = PromptSession.of("t", "sys");

        try (var orch = Orchestrator.of(List.of(provider), List.of(), List.of(tool), 16)) {
            assertEquals(16, orch.maxToolIterations());

            var result = orch.agent(session, MODEL, "go");
            assertEquals("tool-iteration-cap", result.metadata().finishReason());
            assertEquals(16, providerCalls.get(), "provider should be called exactly N=16 times");
            assertEquals(16, toolInvocations.get(), "tool should be dispatched exactly N=16 times");
        }
    }

    @Test
    void capBelowOneThrows() {
        var provider = new ModelProvider() {
            @Override public String id() { return "stub"; }
            @Override public boolean supports(ModelId m) { return "stub".equals(m.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                var meta = new InvocationMetadata(m, Instant.now(), Duration.ofMillis(1),
                        null, null, List.of(), "stop");
                return new InvocationResult(Message.Assistant.of("ok"), meta);
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> Orchestrator.of(List.of(provider), List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> Orchestrator.of(List.of(provider), List.of(), List.of(), -5));
    }
}
