package org.trybunal.api.model;

/**
 * Stable, fully-qualified identifier for a model on a provider.
 *
 * <p>The {@code provider} string MUST match {@link org.trybunal.api.spi.ModelProvider#id()}
 * of the implementation that serves it (e.g. {@code "ollama"}). The {@code name}
 * is the provider-native model tag (e.g. {@code "llama3.1:8b"}).</p>
 *
 * @param provider non-blank provider id
 * @param name     non-blank provider-native model name
 */
public record ModelId(String provider, String name) {
    public ModelId {
        if (provider == null || provider.isBlank())
            throw new IllegalArgumentException("provider required");
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name required");
    }

    @Override
    public String toString() {
        return provider + ":" + name;
    }
}
