package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.tool.ToolResult;

class SubagentsTest {

    private static final ModelId MODEL = new ModelId("stub", "m");

    /** Captures the last conversation passed to {@code invoke} for assertions. */
    private static final class CapturingProvider implements ModelProvider {
        final AtomicReference<List<Message>> lastConversation = new AtomicReference<>();
        private final String reply;
        private final RuntimeException toThrow;

        CapturingProvider(String reply) { this.reply = reply; this.toThrow = null; }
        CapturingProvider(RuntimeException toThrow) { this.reply = null; this.toThrow = toThrow; }

        @Override public String id() { return "stub"; }
        @Override public boolean supports(ModelId m) { return "stub".equals(m.provider()); }
        @Override
        public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
            lastConversation.set(List.copyOf(c));
            if (toThrow != null) throw toThrow;
            var meta = new InvocationMetadata(m, Instant.now(), Duration.ofMillis(1),
                    null, null, List.of(), "stop");
            return new InvocationResult(Message.Assistant.of(reply), meta);
        }
    }

    private static SubagentSpec spec(String name, String systemPrompt) {
        return new SubagentSpec(
                name, "test subagent", MODEL, List.of(), systemPrompt, 4, null);
    }

    @Test
    void successfulRoundTripReturnsToolResultOk() {
        var provider = new CapturingProvider("hello");
        try (Subagent sub = Subagents.asTool(spec("worker", "be helpful"), provider)) {
            ToolResult result = sub.invoke(Map.of("task", "what is up"));
            assertFalse(result.isError(), "successful invocation should not be an error");
            assertEquals("hello", result.content());
        }
    }

    @Test
    void missingTaskArgumentReturnsErrorResult() {
        var provider = new CapturingProvider("unused");
        try (Subagent sub = Subagents.asTool(spec("worker", "be helpful"), provider)) {
            ToolResult result = sub.invoke(Map.of());
            assertTrue(result.isError(), "missing arg should produce an error");
            assertTrue(result.content().contains("task"),
                    "error content should mention the missing arg name; was: " + result.content());
        }
    }

    @Test
    void innerRuntimeExceptionConvertedToErrorResult() {
        var provider = new CapturingProvider(new RuntimeException("boom"));
        try (Subagent sub = Subagents.asTool(spec("worker", "be helpful"), provider)) {
            ToolResult result = sub.invoke(Map.of("task", "trigger"));
            assertTrue(result.isError(), "inner failure should surface as error");
            assertTrue(result.content().contains("RuntimeException"),
                    "error content should mention the exception class; was: " + result.content());
            assertTrue(result.content().contains("boom"),
                    "error content should mention the exception message; was: " + result.content());
        }
    }

    @Test
    void taskMarkerSubstitutedInSystemPrompt() {
        var provider = new CapturingProvider("ok");
        try (Subagent sub = Subagents.asTool(
                spec("worker", "Your job: %TASK%. Do it well."), provider)) {
            sub.invoke(Map.of("task", "summarise filings"));
        }
        List<Message> seen = provider.lastConversation.get();
        assertNotNull(seen, "provider should have been invoked");
        assertFalse(seen.isEmpty());
        Message first = seen.get(0);
        assertInstanceOf(Message.System.class, first);
        assertEquals("Your job: summarise filings. Do it well.", first.content());
    }

    @Test
    void closeShutsDownInnerExecutorAndBlocksFurtherInvocations() {
        var provider = new CapturingProvider("ok");
        Subagent sub = Subagents.asTool(spec("worker", "be helpful"), provider);
        sub.close();
        assertThrows(IllegalStateException.class,
                () -> sub.invoke(Map.of("task", "after close")));
    }

    @Test
    void toolSpecExposesNameDescriptionAndTaskSchema() {
        try (Subagent sub = Subagents.asTool(
                spec("named-worker", "be helpful"), new CapturingProvider("ok"))) {
            assertEquals("named-worker", sub.spec().name());
            assertEquals("test subagent", sub.spec().description());
            Map<String, Object> schema = sub.spec().jsonSchema();
            assertEquals("object", schema.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertNotNull(props, "schema must define properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) props.get("task");
            assertNotNull(task, "task property required");
            assertEquals("string", task.get("type"));
            assertEquals(List.of("task"), schema.get("required"));
        }
    }

    @Test
    void specRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubagentSpec(null, "d", MODEL, List.of(), "p", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SubagentSpec(" ", "d", MODEL, List.of(), "p", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SubagentSpec("n", "d", null, List.of(), "p", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SubagentSpec("n", "d", MODEL, List.of(), null, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SubagentSpec("n", "d", MODEL, List.of(), "p", 0, null));
    }

    @Test
    void specDefensivelyCopiesToolsList() {
        var mutable = new ArrayList<org.trybunal.api.spi.Tool>();
        var s = new SubagentSpec("n", "d", MODEL, mutable, "p", 1, null);
        mutable.add(new org.trybunal.api.spi.Tool() {
            @Override public org.trybunal.api.tool.ToolSpec spec() {
                return new org.trybunal.api.tool.ToolSpec("x", "y", Map.of());
            }
            @Override public ToolResult invoke(Map<String, Object> a) { return ToolResult.ok(""); }
        });
        assertTrue(s.tools().isEmpty(),
                "spec.tools() must not reflect post-construction mutations");
    }
}
