package org.trybunal.examples;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.trybunal.api.model.GenerationParams;
import org.trybunal.api.model.InvocationResult;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.spi.ModelProvider;
import org.trybunal.api.tool.ToolSpec;

/**
 * Diagnostic probe for the tool-call format each configured Ollama model
 * emits in practice. Prints one line per model with the verdict:
 *
 * <pre>
 *   &lt;model&gt;  &lt;verdict&gt;  &lt;detail&gt;
 *
 *   STRUCTURED         — model emitted message.tool_calls
 *   TEXT:PYTHON_TAG    — model emitted &lt;|python_tag|&gt;… in content
 *   TEXT:TOOL_CALLS    — model emitted [TOOL_CALLS] plain form in content
 *   TEXT:TOOL_CALLS_JSON  — [TOOL_CALLS] followed by a JSON array
 *   NO_TOOL_CALL       — model produced no tool call at all
 *   ERROR:&lt;message&gt;    — provider raised; transport or 4xx/5xx
 * </pre>
 *
 * <p>The probe relies on {@code metadata.providerExtras().get("tool_call_origin")}
 * which the Ollama provider stamps for every successful tool-call path
 * (structured + the two text fallbacks). Models that need the fallback show
 * a {@code TEXT:…} verdict on a vanilla install.</p>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code -Dtrybunal.models=a,b,c} — comma list of model names; default
 *       covers the four models from the round-7 baseline.</li>
 * </ul>
 */
public final class ProbeToolFormat {

    private static final String DEFAULT_MODELS =
            "llama3.1:8b,mistral-small:24b,gemma4:26b,gpt-oss:20b";

    public static void main(String[] args) {
        String csv = System.getProperty("trybunal.models", DEFAULT_MODELS);
        String[] models = csv.split(",");

        ModelProvider ollama = locateProvider("ollama");
        if (ollama == null) {
            System.err.println("no ollama provider on classpath — add trybunal-provider-ollama");
            System.exit(2);
            return;
        }

        ToolSpec weatherTool = new ToolSpec(
                "get_weather",
                "Look up the current weather for a city. Always call this; never guess.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "city", Map.of("type", "string", "description", "city name")
                        ),
                        "required", List.of("city")
                ));
        GenerationParams params = new GenerationParams(
                0.0, 256, null, null, Map.of(), List.of(weatherTool));
        List<Message> conversation = List.of(
                new Message.System("You can call tools. When asked about weather, call get_weather."),
                new Message.User("What's the weather in Tokyo right now?"));

        for (String raw : models) {
            String model = raw.trim();
            if (model.isEmpty()) continue;
            System.out.println(probe(ollama, model, conversation, params));
        }
    }

    private static String probe(ModelProvider provider, String model,
                                List<Message> conversation, GenerationParams params) {
        ModelId id = new ModelId("ollama", model);
        InvocationResult result;
        try {
            result = provider.invoke(conversation, id, params);
        } catch (RuntimeException e) {
            return line(model, "ERROR:" + truncate(e.getMessage(), 120), "");
        }
        Object origin = result.metadata().providerExtras().get("tool_call_origin");
        if (origin == null || result.metadata().toolCalls().isEmpty()) {
            return line(model, "NO_TOOL_CALL", truncate(result.reply().content(), 80));
        }
        String o = origin.toString();
        String verdict = switch (o) {
            case "structured" -> "STRUCTURED";
            case "text:python_tag" -> "TEXT:PYTHON_TAG";
            case "text:tool_calls" -> "TEXT:TOOL_CALLS";
            case "text:tool_calls_json" -> "TEXT:TOOL_CALLS_JSON";
            default -> "TEXT:" + o.toUpperCase();
        };
        String first = result.metadata().toolCalls().get(0).toolName();
        return line(model, verdict, first);
    }

    private static String line(String model, String verdict, String detail) {
        return String.format("%-28s %-22s %s", model, verdict, detail);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= n ? oneLine : oneLine.substring(0, n) + "…";
    }

    private static ModelProvider locateProvider(String id) {
        for (ModelProvider p : ServiceLoader.load(ModelProvider.class)) {
            if (id.equals(p.id())) return p;
        }
        return null;
    }

    private ProbeToolFormat() {}
}
