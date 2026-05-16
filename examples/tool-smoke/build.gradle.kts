plugins {
    application
}

dependencies {
    implementation(project(":trybunal-api"))
    implementation(project(":trybunal-core"))
    implementation(project(":trybunal-tool-web-search"))
    implementation(project(":trybunal-tool-web-fetch"))
    implementation(project(":trybunal-tool-browser-playwright"))
    implementation(project(":trybunal-tool-safe-download"))
    implementation(project(":trybunal-tool-citations"))
    runtimeOnly(project(":trybunal-provider-ollama"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("org.trybunal.examples.toolsmoke.ToolSmoke")
}

// Forward -Dtrybunal.* flags from the outer build to the forked JVM.
tasks.named<JavaExec>("run") {
    doFirst {
        System.getProperties().forEach { k, v ->
            val key = k.toString()
            if (key.startsWith("trybunal.")) systemProperty(key, v.toString())
        }
    }
}

// Companion task: one chat call that has to use every tool.
tasks.register<JavaExec>("allTools") {
    group = "application"
    description = "Run the all-tools-required agentic task."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.trybunal.examples.toolsmoke.AllTools")
    doFirst {
        System.getProperties().forEach { k, v ->
            val key = k.toString()
            if (key.startsWith("trybunal.")) systemProperty(key, v.toString())
        }
    }
}
