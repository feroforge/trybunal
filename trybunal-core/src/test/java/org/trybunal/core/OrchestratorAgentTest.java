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

class OrchestratorAgentTest {

    private static final ModelId MODEL = new ModelId("stub", "m");

    private static ModelProvider stubProvider(String id, InvocationResult... replies) {
        var counter = new AtomicInteger();
        return new ModelProvider() {
            @Override public String id() { return id; }
            @Override public boolean supports(ModelId m) { return id.equals(m.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                return replies[counter.getAndIncrement()];
            }
        };
    }

    private static InvocationResult plainResult(ModelId model, String content) {
        var meta = new InvocationMetadata(model, Instant.now(), Duration.ofMillis(1),
                null, null, List.of(), "stop");
        return new InvocationResult(Message.Assistant.of(content), meta);
    }

    private static InvocationResult toolCallResult(ModelId model, ToolCall tc) {
        var meta = new InvocationMetadata(model, Instant.now(), Duration.ofMillis(1),
                null, null, List.of(tc), "tool_use");
        return new InvocationResult(new Message.Assistant("", List.of(tc)), meta);
    }

    private static Tool stubTool(String name, AtomicInteger invocations) {
        var spec = new ToolSpec(name, "stub tool", Map.of());
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolResult invoke(Map<String, Object> args) {
                invocations.incrementAndGet();
                return ToolResult.ok("done");
            }
        };
    }

    // 1. No-tools path is identical to chat
    @Test
    void noToolsAgentBehavesLikeChat() {
        var session = PromptSession.of("t", "sys");

        // Each invocation (chat and agent) consumes one reply from the stub
        var provider = stubProvider("stub",
                plainResult(MODEL, "hello"),
                plainResult(MODEL, "hello"));

        try (var orch = Orchestrator.of(List.of(provider), List.of(), List.of())) {
            var chatResult = orch.chat(session, MODEL, "hi");
            var agentResult = orch.agent(session, MODEL, "hi");
            assertEquals(chatResult.reply().content(), agentResult.reply().content());
        }
    }

    // 2. Tools are discovered & advertised
    @Test
    void registeredToolsContainsToolName() {
        var invocations = new AtomicInteger();
        Tool tool = stubTool("my-tool", invocations);
        var provider = stubProvider("stub", plainResult(MODEL, "ok"));
        var session = PromptSession.of("t", "sys");

        try (var orch = Orchestrator.of(List.of(provider), List.of(), List.of(tool))) {
            assertTrue(orch.registeredTools().contains("my-tool"),
                    "registeredTools() should contain the registered tool name");
            assertEquals(1, orch.registeredTools().size());
        }
    }

    // 3. Tool-name collision throws IllegalStateException
    @Test
    void duplicateToolNameThrows() {
        var invocations = new AtomicInteger();
        Tool t1 = stubTool("same-name", invocations);
        Tool t2 = stubTool("same-name", invocations);
        var provider = stubProvider("stub", plainResult(MODEL, "ok"));

        assertThrows(IllegalStateException.class,
                () -> Orchestrator.of(List.of(provider), List.of(), List.of(t1, t2)));
    }

    // 4. End-to-end ReAct loop: tool dispatched once, final plain reply returned
    @Test
    void endToEndReactLoopDispatchesToolAndReturnsFinalReply() {
        var invocations = new AtomicInteger();
        Tool tool = stubTool("calc", invocations);

        var tc = new ToolCall("id-1", "calc", Map.of("x", 1));
        var firstReply = toolCallResult(MODEL, tc);
        var secondReply = plainResult(MODEL, "final answer");

        var provider = stubProvider("stub", firstReply, secondReply);
        var session = PromptSession.of("t", "sys");

        try (var orch = Orchestrator.of(List.of(provider), List.of(), List.of(tool))) {
            var result = orch.agent(session, MODEL, "compute");
            assertEquals("final answer", result.reply().content());
            assertEquals(1, invocations.get(), "tool should have been invoked exactly once");
        }
    }
}
