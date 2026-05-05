package org.trybunal.api.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptSessionTest {

    private static final ModelId LLAMA = new ModelId("ollama", "llama3.1:8b");
    private static final ModelId MISTRAL = new ModelId("ollama", "mistral:7b");

    @Test
    void resolveForFallsBackToBase() {
        var s = PromptSession.of("t", "base prompt");
        assertEquals("base prompt", s.resolveFor(LLAMA));
    }

    @Test
    void overridesAreModelSpecific() {
        var s = PromptSession.of("t", "base").withOverride(LLAMA, "for-llama");
        assertEquals("for-llama", s.resolveFor(LLAMA));
        assertEquals("base", s.resolveFor(MISTRAL));
    }

    @Test
    void withOverrideIsImmutable() {
        var s1 = PromptSession.of("t", "base");
        var s2 = s1.withOverride(LLAMA, "for-llama");
        assertEquals("base", s1.resolveFor(LLAMA));
        assertNotSame(s1, s2);
    }

    @Test
    void materializeStartsWithSystemPrompt() {
        var s = PromptSession.of("t", "BASE")
                .withOverride(LLAMA, "OVERRIDE")
                .withSeed(new Message.User("hi"));
        List<Message> conv = s.materialize(LLAMA);
        assertEquals(2, conv.size());
        assertInstanceOf(Message.System.class, conv.get(0));
        assertEquals("OVERRIDE", conv.get(0).content());
        assertInstanceOf(Message.User.class, conv.get(1));
    }

    @Test
    void rejectsNullBasePrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> PromptSession.of("t", null));
    }
}
