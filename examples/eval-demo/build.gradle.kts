plugins { application }

dependencies {
    implementation(project(":trybunal-core"))
    runtimeOnly(project(":trybunal-provider-ollama"))
    runtimeOnly(project(":trybunal-evaluator-assertions"))
    runtimeOnly(project(":trybunal-evaluator-llm-judge"))
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
}

application {
    mainClass.set("org.trybunal.examples.EvalDemo")
}
