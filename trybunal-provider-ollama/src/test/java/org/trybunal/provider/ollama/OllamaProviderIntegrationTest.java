package org.trybunal.provider.ollama;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

/**
 * Live integration test. Skipped unless {@code OLLAMA_HOST} is set.
 *
 * <p>Run locally with:
 * {@code OLLAMA_URL=http://localhost:11434 ./gradlew :trybunal-provider-ollama:test}.</p>
 */
class OllamaProviderIntegrationTest {

    @Test
    void respondsToHello() {
        String host = System.getenv("OLLAMA_URL");
        assumeTrue(host != null && !host.isBlank(),
                "OLLAMA_URL not set; skipping live integration test");

        String modelName = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.1:8b");
        var provider = new OllamaProvider(host);
        var modelId = new ModelId("ollama", modelName);

        var result = provider.invoke(
                List.of(new Message.System("Be brief."), new Message.User("Say 'hi'.")),
                modelId,
                GenerationParams.defaults()
        );

        assertNotNull(result.reply());
        assertFalse(result.reply().content().isBlank(), "expected non-empty reply");
    }

    @Test
    void supportsRejectsForeignProvider() {
        var p = new OllamaProvider("http://localhost:11434");
        assertTrue(p.supports(new ModelId("ollama", "x")));
        assertFalse(p.supports(new ModelId("openai", "gpt-4")));
    }
}
