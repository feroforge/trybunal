dependencies {
    implementation(project(":trybunal-api"))
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
