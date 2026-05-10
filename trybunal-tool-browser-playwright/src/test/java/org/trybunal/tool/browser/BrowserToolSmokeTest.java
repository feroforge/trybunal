package org.trybunal.tool.browser;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link BrowserTool}.
 *
 * <p>Gated behind the {@code TRYBUNAL_BROWSER_TESTS=1} environment variable.
 * Gradle skips this test task unless that variable is set. To run locally:</p>
 * <pre>
 *   npx playwright install chromium
 *   TRYBUNAL_BROWSER_TESTS=1 ./gradlew :trybunal-tool-browser-playwright:test
 * </pre>
 */
class BrowserToolSmokeTest {

    @Test
    void rendersExampleDotCom() {
        BrowserTool tool = new BrowserTool();
        ToolResult result = tool.invoke(Map.of("url", "https://example.com"));
        assertFalse(result.isError(), "Expected success but got error: " + result.content());
        assertTrue(result.content().contains("Example Domain"),
                "Expected 'Example Domain' in rendered text, got: " + result.content().substring(0, Math.min(200, result.content().length())));
        assertFalse(result.citations().isEmpty(), "Expected at least one citation");
        assertTrue(result.citations().get(0).source().localPath().isPresent(),
                "Expected screenshot path in source localPath");
    }

    @Test
    void ssrfGuardRejectsLocalhost() {
        BrowserTool tool = new BrowserTool();
        ToolResult result = tool.invoke(Map.of("url", "http://localhost:1"));
        assertTrue(result.isError(), "Expected SSRF refusal but got: " + result.content());
        assertTrue(result.content().toLowerCase().contains("refused") || result.content().toLowerCase().contains("private"),
                "Expected 'refused' or 'private' in error, got: " + result.content());
    }
}
