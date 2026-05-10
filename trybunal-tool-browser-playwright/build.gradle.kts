dependencies {
    implementation(project(":trybunal-api"))
    implementation("com.microsoft.playwright:playwright:1.47.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // Smoke test requires Playwright browsers installed locally.
    // Skip unless TRYBUNAL_BROWSER_TESTS=1.
    onlyIf { System.getenv("TRYBUNAL_BROWSER_TESTS") == "1" }
}
