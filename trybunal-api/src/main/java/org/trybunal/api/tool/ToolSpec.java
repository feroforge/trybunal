package org.trybunal.api.tool;

import java.util.Map;

/**
 * What a {@link org.trybunal.api.spi.Tool} advertises to the model.
 *
 * <p>{@code jsonSchema} is a provider-agnostic JSON Schema document represented
 * as nested {@link Map}s and {@link java.util.List}s of JSON-compatible
 * primitives. Provider modules translate it to their own wire format.</p>
 *
 * @param name        stable identifier; non-blank; matches {@code [a-zA-Z0-9_\-]{1,64}}
 * @param description natural-language description shown to the model; non-blank
 * @param jsonSchema  JSON Schema for the tool's arguments; defensively copied; never null
 */
public record ToolSpec(String name, String description, Map<String, Object> jsonSchema) {
    public ToolSpec {
        if (name == null || !name.matches("[a-zA-Z0-9_\\-]{1,64}"))
            throw new IllegalArgumentException("name must match [a-zA-Z0-9_-]{1,64}");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("description required");
        jsonSchema = jsonSchema == null ? Map.of() : Map.copyOf(jsonSchema);
    }
}
