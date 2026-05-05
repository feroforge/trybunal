package org.trybunal.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationMetadata;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.api.spi.ModelProvider;

class OrchestratorTest {

    @Test
    void chatRoutesUserMessageThroughHarness() throws Exception {
        var captured = new AtomicReference<List<Message>>();
        ModelProvider stub = new ModelProvider() {
            @Override public String id() { return "stub"; }
            @Override public boolean supports(ModelId modelId) { return "stub".equals(modelId.provider()); }
            @Override public InvocationResult invoke(List<Message> c, ModelId m, GenerationParams p) {
                captured.set(c);
                return new InvocationResult(
                        Message.Assistant.of("hello back"),
                        new InvocationMetadata(m, Instant.now(), Duration.ZERO, null, null, List.of(), "stop"));
            }
        };

        try (var orch = Orchestrator.of(stub)) {
            var session = PromptSession.of("t", "you are a test bot");
            var res = orch.chat(session, new ModelId("stub", "x"), "hi");

            assertEquals("hello back", res.reply().content());
            var conv = captured.get();
            assertEquals(2, conv.size());
            assertInstanceOf(Message.System.class, conv.get(0));
            assertInstanceOf(Message.User.class, conv.get(1));
            assertEquals("hi", conv.get(1).content());
        }
    }

    @Test
    void unknownProviderThrows() {
        try (var orch = Orchestrator.of()) {
            var session = PromptSession.of("t", "sys");
            assertThrows(IllegalStateException.class,
                    () -> orch.chat(session, new ModelId("nope", "x"), "hi"));
        }
    }
}
