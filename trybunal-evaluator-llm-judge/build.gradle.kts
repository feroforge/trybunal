dependencies {
    api(project(":trybunal-api"))
    // Jackson for parsing the judge's JSON verdict. Only the databind module.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}

// No public classes yet; suppress "no classes to document" error until Task 07.
tasks.withType<Javadoc> {
    isFailOnError = false
}
