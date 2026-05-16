plugins {
    application
}

dependencies {
    implementation(project(":trybunal-core"))
    implementation(project(":trybunal-provider-ollama"))
    implementation(project(":trybunal-tool-mocks"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("org.trybunal.examples.probe.ProbeContextLimit")
}

// Forward -Dtrybunal.* flags to the forked JVM that runs the probe.
tasks.named<JavaExec>("run") {
    doFirst {
        System.getProperties().forEach { k, v ->
            val key = k.toString()
            if (key.startsWith("trybunal.") || key.startsWith("org.slf4j.") || key == "OLLAMA_HOST") {
                systemProperty(key, v.toString())
            }
        }
    }
}
