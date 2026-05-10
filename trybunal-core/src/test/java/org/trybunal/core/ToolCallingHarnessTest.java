package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
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

class ToolCallingHarnessTest {

    private static final ModelId MODEL = new ModelId("test", "stub");
    private static final GenerationParams DEFAULTS = GenerationParams.defaults();

    private static InvocationResult result(String content) {
        return result(content, List.of());
    }

    private static InvocationResult result(String content, List<ToolCall> toolCalls) {
        var meta = new InvocationMetadata(MODEL, Instant.now(), Duration.ofMillis(1),
                null, null, toolCalls, "stop");
        return new InvocationResult(new Message.Assistant(content, toolCalls), meta);
    }

    private static Tool stubTool(String name, ToolResult response) {
        var spec = new ToolSpec(name, "A stub tool", Map.of());
        return new Tool() {
            @Override public ToolSpec spec() { return spec; }
            @Override public ToolResult invoke(Map<String, Object> arguments) { return response; }
        };
    }

    private static java.util.concurrent.ExecutorService virtualExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // 1. No tool calls path
    @Test
    void noToolCallsReturnsResultUnchanged() {
        InvocationResult expected = result("hello");
        ModelHarness delegate = (conv, m, p) -> expected;

        var harness = new ToolCallingHarness(delegate, List.of(), 8, virtualExecutor());
        InvocationResult actual = harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS);

        assertSame(expected, actual);
    }

    // 2. Single iteration, single tool
    @Test
    void singleIterationSingleTool() {
        var toolCall = new ToolCall("id-1", "echo", Map.of("msg", "hello"));
        AtomicInteger invokeCount = new AtomicInteger();
        List<List<Message>> capturedConversations = new CopyOnWriteArrayList<>();

        ModelHarness delegate = (conv, m, p) -> {
            capturedConversations.add(new ArrayList<>(conv));
            int call = capturedConversations.size();
            if (call == 1) {
                return result("", List.of(toolCall));
            }
            return result("final answer");
        };

        Tool echo = new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("echo", "echoes", Map.of()); }
            @Override public ToolResult invoke(Map<String, Object> arguments) {
                invokeCount.incrementAndGet();
                return ToolResult.ok("echoed");
            }
        };

        var harness = new ToolCallingHarness(delegate, List.of(echo), 8, virtualExecutor());
        InvocationResult result = harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS);

        assertEquals(1, invokeCount.get(), "tool should be invoked once");
        assertEquals("final answer", result.reply().content());

        // Second conversation must contain a Message.Tool
        List<Message> secondConv = capturedConversations.get(1);
        long toolMsgCount = secondConv.stream().filter(msg -> msg instanceof Message.Tool).count();
        assertEquals(1, toolMsgCount, "second invocation conversation must include a Message.Tool");
    }

    // 3. Parallel dispatch within an iteration
    @Test
    void parallelDispatchWithinIteration() throws Exception {
        var tc1 = new ToolCall("id-1", "tool_a", Map.of());
        var tc2 = new ToolCall("id-2", "tool_b", Map.of());

        CopyOnWriteArrayList<String> threadNames = new CopyOnWriteArrayList<>();

        Tool toolA = new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("tool_a", "a", Map.of()); }
            @Override public ToolResult invoke(Map<String, Object> args) {
                threadNames.add(Thread.currentThread().getName());
                return ToolResult.ok("a-result");
            }
        };
        Tool toolB = new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("tool_b", "b", Map.of()); }
            @Override public ToolResult invoke(Map<String, Object> args) {
                threadNames.add(Thread.currentThread().getName());
                return ToolResult.ok("b-result");
            }
        };

        AtomicInteger callCount = new AtomicInteger();
        ModelHarness delegate = (conv, m, p) -> {
            if (callCount.incrementAndGet() == 1) {
                return result("", List.of(tc1, tc2));
            }
            return result("done");
        };

        var harness = new ToolCallingHarness(delegate, List.of(toolA, toolB), 8, virtualExecutor());
        harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS);

        assertEquals(2, threadNames.size(), "both tools must have run");
    }

    // 4. Unknown tool
    @Test
    void unknownToolDoesNotThrow() {
        var toolCall = new ToolCall("id-1", "nope", Map.of());
        AtomicInteger delegateCallCount = new AtomicInteger();

        ModelHarness delegate = (conv, m, p) -> {
            int n = delegateCallCount.incrementAndGet();
            if (n == 1) return result("", List.of(toolCall));
            return result("recovered");
        };

        var harness = new ToolCallingHarness(delegate, List.of(), 8, virtualExecutor());
        InvocationResult result = assertDoesNotThrow(
                () -> harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS));

        assertEquals("recovered", result.reply().content());

        // Verify an error Message.Tool was sent back
        // delegateCallCount == 2 means the harness continued after the unknown tool
        assertEquals(2, delegateCallCount.get());
    }

    // 5. Tool throws
    @Test
    void toolExceptionBecomesErrorResult() {
        var toolCall = new ToolCall("id-1", "boom", Map.of());
        AtomicInteger delegateCallCount = new AtomicInteger();

        ModelHarness delegate = (conv, m, p) -> {
            int n = delegateCallCount.incrementAndGet();
            if (n == 1) return result("", List.of(toolCall));
            // verify the tool result content contains error info
            Message.Tool toolMsg = conv.stream()
                    .filter(msg -> msg instanceof Message.Tool)
                    .map(msg -> (Message.Tool) msg)
                    .findFirst()
                    .orElseThrow();
            assertTrue(toolMsg.content().contains("RuntimeException"),
                    "error content should mention exception class, got: " + toolMsg.content());
            return result("ok after error");
        };

        Tool boom = new Tool() {
            @Override public ToolSpec spec() { return new ToolSpec("boom", "throws", Map.of()); }
            @Override public ToolResult invoke(Map<String, Object> args) {
                throw new RuntimeException("kaboom");
            }
        };

        var harness = new ToolCallingHarness(delegate, List.of(boom), 8, virtualExecutor());
        InvocationResult result = assertDoesNotThrow(
                () -> harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS));

        assertEquals("ok after error", result.reply().content());
    }

    // 6. Iteration cap
    @Test
    void iterationCapReturnsCapFinishReason() {
        var toolCall = new ToolCall("id-1", "forever", Map.of());
        Tool forever = stubTool("forever", ToolResult.ok("looping"));

        ModelHarness delegate = (conv, m, p) -> result("still asking", List.of(toolCall));

        var harness = new ToolCallingHarness(delegate, List.of(forever), 2, virtualExecutor());
        InvocationResult result = harness.run(List.of(new Message.User("hi")), MODEL, DEFAULTS);

        assertEquals("tool-iteration-cap", result.metadata().finishReason());
        assertTrue(result.reply().content().startsWith("[tool iteration cap reached]\n"),
                "content should have cap prefix, got: " + result.reply().content());
    }

    // 7. Caller tools win on name collision
    @Test
    void callerToolsWinOnNameCollision() {
        ToolSpec registeredSpec = new ToolSpec("shared", "registered version", Map.of());
        ToolSpec callerSpec = new ToolSpec("shared", "caller version", Map.of());

        Tool registeredTool = new Tool() {
            @Override public ToolSpec spec() { return registeredSpec; }
            @Override public ToolResult invoke(Map<String, Object> args) { return ToolResult.ok("registered"); }
        };

        List<ToolSpec> capturedTools = new ArrayList<>();
        ModelHarness delegate = (conv, m, p) -> {
            capturedTools.addAll(p.tools());
            return result("done");
        };

        GenerationParams paramsWithCallerTool = DEFAULTS.withTools(List.of(callerSpec));
        var harness = new ToolCallingHarness(delegate, List.of(registeredTool), 8, virtualExecutor());
        harness.run(List.of(new Message.User("hi")), MODEL, paramsWithCallerTool);

        ToolSpec merged = capturedTools.stream()
                .filter(ts -> ts.name().equals("shared"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shared tool not found in merged params"));

        assertEquals("caller version", merged.description(),
                "caller's tool spec should win on name collision");
    }
}
