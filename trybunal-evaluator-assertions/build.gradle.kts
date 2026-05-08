dependencies {
    api(project(":trybunal-api"))
}

// No public classes yet; suppress "no classes to document" error until Task 04.
tasks.withType<Javadoc> {
    isFailOnError = false
}
