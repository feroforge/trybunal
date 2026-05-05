package org.trybunal.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The unit of iteration in Trybunal. Holds a canonical {@code basePrompt}
 * plus per-model overrides for cases where one model needs a tweaked phrasing.
 *
 * <p>Sessions are immutable; evolve them with {@link #withOverride}. The
 * {@link #materialize} helper produces the conversation prefix
 * (system + seeded messages) ready to hand to a provider.</p>
 */
public record PromptSession(
        UUID id,
        String name,
        String basePrompt,
        Map<ModelId, String> overrides,
        List<Message> seedMessages,
        GenerationParams params,
        Instant createdAt
) {
    public PromptSession {
        if (basePrompt == null) throw new IllegalArgumentException("basePrompt required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name required");
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        seedMessages = seedMessages == null ? List.of() : List.copyOf(seedMessages);
        if (params == null) params = GenerationParams.defaults();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Builds a fresh session with no overrides and default generation params. */
    public static PromptSession of(String name, String basePrompt) {
        return new PromptSession(null, name, basePrompt, Map.of(), List.of(), null, null);
    }

    /** Returns the prompt to use for {@code modelId}, falling back to the base. */
    public String resolveFor(ModelId modelId) {
        return Optional.ofNullable(overrides.get(modelId)).orElse(basePrompt);
    }

    /** Returns a new session with an override added or replaced for {@code modelId}. */
    public PromptSession withOverride(ModelId modelId, String prompt) {
        if (modelId == null) throw new IllegalArgumentException("modelId required");
        if (prompt == null) throw new IllegalArgumentException("prompt required");
        var next = new HashMap<>(overrides);
        next.put(modelId, prompt);
        return new PromptSession(id, name, basePrompt, next, seedMessages, params, createdAt);
    }

    /** Returns a new session with {@code message} appended to the seed conversation. */
    public PromptSession withSeed(Message message) {
        if (message == null) throw new IllegalArgumentException("message required");
        var next = new ArrayList<>(seedMessages);
        next.add(message);
        return new PromptSession(id, name, basePrompt, overrides, next, params, createdAt);
    }

    /**
     * Materializes the conversation for {@code modelId}: the resolved system
     * prompt, followed by every seeded message in order.
     */
    public List<Message> materialize(ModelId modelId) {
        var msgs = new ArrayList<Message>(seedMessages.size() + 1);
        msgs.add(new Message.System(resolveFor(modelId)));
        msgs.addAll(seedMessages);
        return List.copyOf(msgs);
    }
}
