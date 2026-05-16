package org.trybunal.api.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContextWindowTest {

    @Test
    void componentsAreReadBack() {
        var cw = new ContextWindow(1000, 4096);
        assertEquals(1000, cw.promptTokens());
        assertEquals(4096, cw.numCtx());
    }

    @Test
    void headroomIsDifference() {
        var cw = new ContextWindow(3840, 4096);
        assertEquals(256, cw.headroom());
    }

    @Test
    void headroomClampsToZeroWhenOverflowing() {
        var cw = new ContextWindow(5000, 4096);
        assertEquals(0, cw.headroom(), "headroom must never be negative");
    }

    @Test
    void headroomIsNumCtxWhenPromptIsZero() {
        var cw = new ContextWindow(0, 4096);
        assertEquals(4096, cw.headroom());
    }

    @Test
    void fillRatioInUnitInterval() {
        assertEquals(0.0, new ContextWindow(0, 4096).fillRatio(), 1e-9);
        assertEquals(0.5, new ContextWindow(2048, 4096).fillRatio(), 1e-9);
        assertEquals(1.0, new ContextWindow(4096, 4096).fillRatio(), 1e-9);
    }

    @Test
    void fillRatioClampsToOneWhenOverflowing() {
        assertEquals(1.0, new ContextWindow(8192, 4096).fillRatio(), 1e-9,
                "fillRatio must never exceed 1.0");
    }

    @Test
    void rejectsNegativePromptTokens() {
        assertThrows(IllegalArgumentException.class, () -> new ContextWindow(-1, 4096));
    }

    @Test
    void rejectsZeroNumCtx() {
        assertThrows(IllegalArgumentException.class, () -> new ContextWindow(0, 0));
    }

    @Test
    void rejectsNegativeNumCtx() {
        assertThrows(IllegalArgumentException.class, () -> new ContextWindow(10, -4096));
    }
}
