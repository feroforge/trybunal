package org.trybunal.tool.citations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

class CiteToolTest {

    private static final String VALID_SHA256 = "a".repeat(64);
    private static final String VALID_SHA256_B = "b".repeat(64);

    private CitationStore store;
    private CiteTool tool;

    @BeforeEach
    void setUp() {
        store = new CitationStore();
        tool = new CiteTool(store);
    }

    /** Test 1: Two citations stored; snapshot returns both in insertion order. */
    @Test
    void twoCitationsStoredInOrder() {
        invoke(Map.of("url", "https://example.com/a", "excerpt", "first excerpt", "sha256", VALID_SHA256));
        invoke(Map.of("url", "https://example.com/b", "excerpt", "second excerpt", "sha256", VALID_SHA256_B));

        List<Citation> snapshot = store.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("first excerpt", snapshot.get(0).excerpt());
        assertEquals("second excerpt", snapshot.get(1).excerpt());
    }

    /** Test 2: Bad sha256 → ToolResult.error, store unchanged. */
    @Test
    void badSha256ReturnsErrorAndStoreUnchanged() {
        ToolResult result = invoke(Map.of(
                "url", "https://example.com",
                "excerpt", "some text",
                "sha256", "not-a-valid-sha256"));

        assertTrue(result.isError(), "Expected an error result");
        assertTrue(store.snapshot().isEmpty(), "Store should be unchanged after bad sha256");
    }

    /** Test 3: Two citations from the same URI render under one numbered entry. */
    @Test
    void sameUriCollapsedInReport() {
        String uri = "https://example.com/doc";
        invoke(Map.of("url", uri, "excerpt", "first quote", "sha256", VALID_SHA256));
        invoke(Map.of("url", uri, "excerpt", "second quote", "sha256", VALID_SHA256));

        String report = CitationReport.renderMarkdown(store.snapshot());

        // Only one numbered entry (starts with "1.")
        assertTrue(report.contains("1."), "Report should have entry 1");
        assertFalse(report.contains("2."), "Report should NOT have a second numbered entry for same URI");

        // Both excerpts present
        assertTrue(report.contains("first quote"), "Should contain first excerpt");
        assertTrue(report.contains("second quote"), "Should contain second excerpt");
    }

    /** Test 4: clear() empties the store. */
    @Test
    void clearEmptiesStore() {
        invoke(Map.of("url", "https://example.com", "excerpt", "something", "sha256", VALID_SHA256));
        assertFalse(store.snapshot().isEmpty());

        store.clear();
        assertTrue(store.snapshot().isEmpty(), "Store should be empty after clear()");
    }

    /** Test 5: 100 virtual threads each add a citation; snapshot has exactly 100 entries. */
    @Test
    void concurrentAddsPreserveAllEntries() throws Exception {
        int count = 100;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    tool.invoke(Map.of(
                            "url", "https://example.com/" + idx,
                            "excerpt", "excerpt " + idx,
                            "sha256", VALID_SHA256));
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(count, store.snapshot().size(), "All 100 citations should be in the store");
    }

    private ToolResult invoke(Map<String, Object> args) {
        return tool.invoke(args);
    }
}
