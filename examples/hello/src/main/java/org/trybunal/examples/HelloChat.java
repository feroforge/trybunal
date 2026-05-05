package org.trybunal.examples;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.PromptSession;
import org.trybunal.core.Orchestrator;

/**
 * Smoke runner: User → Orchestrator → Ollama → reply.
 *
 * <p>Reads one line from stdin, sends it to {@code ollama:llama3.1:8b}
 * (override with {@code -Dtrybunal.model=...}), and prints reply + latency.</p>
 */
public final class HelloChat {

    public static void main(String[] args) throws Exception {
        String modelName = System.getProperty("trybunal.model", "gemma4:26b");
        ModelId modelId = new ModelId("ollama", modelName);

        PromptSession session = PromptSession.of(
                "hello",
                "You are a concise, friendly assistant. Answer in one sentence."
        );

        try (Orchestrator orchestrator = Orchestrator.autoDiscover()) {
            if (!orchestrator.registeredProviders().contains("ollama")) {
                System.err.println("No ollama provider on classpath. "
                        + "Make sure trybunal-provider-ollama is a runtime dependency.");
                System.exit(2);
            }

            System.out.print("you> ");
            System.out.flush();
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                System.err.println("no input");
                System.exit(1);
            }
            System.out.println("sending message");
            InvocationResult result = orchestrator.chat(session, modelId, line);
            System.out.println("assistant> " + result.reply().content());
            System.out.println();
            System.out.printf("[%s | %d ms | finish=%s]%n",
                    modelId,
                    result.metadata().latency().toMillis(),
                    String.valueOf(result.metadata().finishReason()));
        }
    }

    private HelloChat() {}
}
