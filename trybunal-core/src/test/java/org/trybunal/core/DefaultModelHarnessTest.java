package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelProvider;

class DefaultModelHarnessTest {

    private static final ModelId M = new ModelId("fake", "x");

    @Test
    void populatesLatencyEvenWhenProviderReportsZero() {
        ModelProvider p = new ModelProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean supports(ModelId modelId) { return true; }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                return new InvocationResult(
                        Message.Assistant.of("ok"),
                        new InvocationMetadata(m, Instant.now(), Duration.ZERO,
                                10, 2, List.of(), "stop"));
            }
        };
        var harness = new DefaultModelHarness(p);
        var result = harness.run(List.of(new Message.User("hi")), M, GenerationParams.defaults());
        assertTrue(result.metadata().latency().toMillis() >= 5,
                "expected measured latency, got " + result.metadata().latency());
        assertEquals(10, result.metadata().promptTokens());
        assertEquals("stop", result.metadata().finishReason());
    }

    @Test
    void rejectsEmptyConversation() {
        ModelProvider p = new ModelProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean supports(ModelId modelId) { return true; }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams pp) {
                throw new AssertionError("should not be called");
            }
        };
        var harness = new DefaultModelHarness(p);
        assertThrows(IllegalArgumentException.class,
                () -> harness.run(List.of(), M, GenerationParams.defaults()));
    }
}
