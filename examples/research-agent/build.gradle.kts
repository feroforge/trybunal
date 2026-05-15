plugins {
    application
}

dependencies {
    implementation(project(":trybunal-core"))
    implementation(project(":trybunal-tool-citations"))
    implementation(project(":trybunal-evaluator-llm-judge"))
    runtimeOnly(project(":trybunal-provider-ollama"))
    runtimeOnly(project(":trybunal-evaluator-assertions"))
    runtimeOnly(project(":trybunal-tool-web-search"))
    runtimeOnly(project(":trybunal-tool-web-fetch"))
    runtimeOnly(project(":trybunal-tool-browser-playwright"))
    runtimeOnly(project(":trybunal-tool-safe-download"))
    runtimeOnly(project(":trybunal-tool-citations"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    // Default runner: end-to-end gathering. Switch with -PmainClass or by
    // running the eval-specific task below.
    mainClass.set("org.trybunal.examples.thesis.ThesisAgent")
}

// Companion task: multi-model evaluation of the gathering sub-agent.
// Usage: ./gradlew :examples:research-agent:thesisEval \
//          -Dtrybunal.models=llama3.1:8b,mistral-small:24b -Dtrybunal.ticker=AAPL
tasks.register<JavaExec>("thesisEval") {
    group = "application"
    description = "Run the multi-model thesis-gathering evaluation."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.trybunal.examples.thesis.ThesisEval")
    // Forward -Dtrybunal.* flags to the forked JVM at execution time.
    // Doing this in doFirst (not at config time) is what makes -D flags
    // actually reach the eval; otherwise systemProperties is frozen
    // before the user's command line is parsed.
    doFirst {
        System.getProperties().forEach { k, v ->
            val key = k.toString()
            if (key.startsWith("trybunal.")) systemProperty(key, v.toString())
        }
    }
}

// Companion task: original single-call ResearchAgent demo, kept reachable.
tasks.register<JavaExec>("researchAgent") {
    group = "application"
    description = "Run the original single-call ResearchAgent (one ticker, one prompt)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.trybunal.examples.ResearchAgent")
}
