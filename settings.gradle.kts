plugins {
    // Auto-downloads matching JDKs for Gradle toolchains when needed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "trybunal"

include(
    "trybunal-api",
    "trybunal-core",
    "trybunal-provider-ollama",
    "trybunal-evaluator-assertions",
    "trybunal-evaluator-llm-judge",
    "trybunal-tool-web-search",
    "trybunal-tool-web-fetch",
    "trybunal-tool-browser-playwright",
    "trybunal-tool-safe-download",
    "trybunal-tool-citations",
    "examples:hello",
    "examples:eval-demo",
    "examples:agentic-eval",
    "examples:probe-tool-format"
)
