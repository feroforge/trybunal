plugins {
    `java-library`
}

allprojects {
    group = "org.trybunal"
    version = "0.1.0-SNAPSHOT"
}

// Skip container projects (e.g. ":examples") that only group children.
configure(subprojects.filter { it.path != ":examples" }) {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        withSourcesJar()
        withJavadocJar()
    }

    repositories { mavenCentral() }

    dependencies {
        "implementation"("org.slf4j:slf4j-api:2.0.13")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
        "testRuntimeOnly"("org.slf4j:slf4j-simple:2.0.13")
    }

    tasks.withType<Test> { useJUnitPlatform() }
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }
}
