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
    "examples:hello"
)
