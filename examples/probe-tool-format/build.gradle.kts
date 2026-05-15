plugins {
    application
}

dependencies {
    implementation(project(":trybunal-core"))
    runtimeOnly(project(":trybunal-provider-ollama"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("org.trybunal.examples.ProbeToolFormat")
}
