package org.trybunal.api.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToolDomainTest {

    private static final String VALID_SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static Source source() {
        return new Source(URI.create("https://example.com"), "Example",
                Instant.EPOCH, VALID_SHA, Optional.empty());
    }

    @Test
    void toolSpecRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSpec("", "desc", Map.of()));
    }

    @Test
    void toolSpecRejectsBadPatternName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSpec("bad name!", "desc", Map.of()));
    }

    @Test
    void toolSpecRejectsBlankDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSpec("ok_name", "  ", Map.of()));
    }

    @Test
    void toolSpecCopiesJsonSchema() {
        var mutable = new HashMap<String, Object>();
        mutable.put("type", "object");
        var spec = new ToolSpec("ok-name_1", "desc", mutable);
        mutable.put("type", "string");
        assertEquals("object", spec.jsonSchema().get("type"));
        assertThrows(UnsupportedOperationException.class,
                () -> spec.jsonSchema().put("foo", "bar"));
    }

    @Test
    void toolSpecAcceptsNullSchemaAsEmpty() {
        var spec = new ToolSpec("n", "desc", null);
        assertTrue(spec.jsonSchema().isEmpty());
    }

    @Test
    void sourceRejectsShortSha() {
        assertThrows(IllegalArgumentException.class,
                () -> new Source(URI.create("https://x"), "t", Instant.EPOCH, "abc", Optional.empty()));
    }

    @Test
    void sourceRejectsUppercaseSha() {
        var bad = "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef";
        assertThrows(IllegalArgumentException.class,
                () -> new Source(URI.create("https://x"), "t", Instant.EPOCH, bad, Optional.empty()));
    }

    @Test
    void sourceAcceptsValidSha() {
        var s = new Source(URI.create("https://x"), null, Instant.EPOCH, VALID_SHA, null);
        assertEquals("", s.title());
        assertEquals(Optional.empty(), s.localPath());
    }

    @Test
    void citationRejectsNegativeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new Citation(source(), "hello", -1, 1));
    }

    @Test
    void citationRejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new Citation(source(), "hello", 3, 1));
    }

    @Test
    void citationRejectsEndPastLength() {
        assertThrows(IllegalArgumentException.class,
                () -> new Citation(source(), "hello", 0, 99));
    }

    @Test
    void citationAcceptsValidOffsets() {
        var c = new Citation(source(), "hello", 0, 5);
        assertEquals(5, c.endOffset());
    }

    @Test
    void toolResultOkIsNotError() {
        var r = ToolResult.ok("hi");
        assertFalse(r.isError());
        assertEquals("hi", r.content());
        assertTrue(r.citations().isEmpty());
    }

    @Test
    void toolResultErrorIsError() {
        var r = ToolResult.error("oops");
        assertTrue(r.isError());
        assertEquals("oops", r.content());
    }

    @Test
    void toolResultCopiesCitations() {
        var citations = new java.util.ArrayList<Citation>();
        citations.add(new Citation(source(), "x", 0, 1));
        var r = new ToolResult("body", false, citations);
        citations.clear();
        assertEquals(1, r.citations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> r.citations().add(new Citation(source(), "y", 0, 1)));
    }

    @Test
    void toolResultAcceptsNullCitations() {
        var r = new ToolResult("body", false, null);
        assertEquals(List.of(), r.citations());
    }
}
