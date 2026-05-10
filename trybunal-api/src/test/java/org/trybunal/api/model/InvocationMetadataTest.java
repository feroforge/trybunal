package org.trybunal.api.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvocationMetadataTest {

    private static final ModelId MODEL = new ModelId("ollama", "test:latest");

    @Test
    void sevenArgConstructorDefaultsExtrasToEmpty() {
        var m = new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), "stop");

        assertNotNull(m.providerExtras(), "extras must never be null");
        assertTrue(m.providerExtras().isEmpty(), "extras default to empty map");
    }

    @Test
    void canonicalConstructorAcceptsExtras() {
        var m = new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), "stop", Map.of("thinking", "<reasoning>"));

        assertEquals("<reasoning>", m.providerExtras().get("thinking"));
    }

    @Test
    void extrasMapIsDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("thinking", "before");
        var m = new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), "stop", mutable);

        mutable.put("thinking", "after");
        assertEquals("before", m.providerExtras().get("thinking"),
                "external mutation must not leak into the record");
    }

    @Test
    void extrasMapIsUnmodifiable() {
        var m = new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), "stop", Map.of("k", "v"));

        assertThrows(UnsupportedOperationException.class,
                () -> m.providerExtras().put("k2", "v2"));
    }

    @Test
    void nullExtrasNormalizedToEmpty() {
        var m = new InvocationMetadata(MODEL, Instant.EPOCH, Duration.ZERO,
                null, null, List.of(), "stop", null);

        assertTrue(m.providerExtras().isEmpty());
    }
}
