package org.trybunal.compactor.toolresults;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;
import org.trybunal.api.model.ToolCall;
import org.trybunal.api.spi.CompactionRequest;
import org.trybunal.api.spi.CompactionResult;

class ToolResultsCompactorTest {

    private static final ModelId MODEL = new ModelId("test", "stub");

    private static String large(String prefix) {
        return prefix + "x".repeat(2000);
    }

    @Test
    void idIsToolResults() {
        assertEquals("tool-results", new ToolResultsCompactor().id());
    }

    @Test
    void noOversizedToolResultsReturnsUnchanged() {
        var conv = List.<Message>of(
                new Message.System("sys"),
                new Message.User("hi"),
                new Message.Assistant("ok", List.of()));
        var req = new CompactionRequest(conv, null, 1000, MODEL);

        CompactionResult res = new ToolResultsCompactor().compact(req);

        assertEquals(0, res.messagesRewritten());
        assertEquals(0, res.messagesDropped());
        assertEquals(conv, res.conversation());
    }

    @Test
    void threeLargeToolResultsRewritesOlderTwo() {
        var tc1 = new ToolCall("call-1", "fetch", Map.of());
        var tc2 = new ToolCall("call-2", "fetch", Map.of());
        var tc3 = new ToolCall("call-3", "fetch", Map.of());

        List<Message> conv = List.of(
                new Message.System("sys"),
                new Message.User("kick off"),
                new Message.Assistant("", List.of(tc1)),
                new Message.Tool("call-1", large("ONE-")),
                new Message.Assistant("", List.of(tc2)),
                new Message.Tool("call-2", large("TWO-")),
                new Message.Assistant("", List.of(tc3)),
                new Message.Tool("call-3", large("THREE-")));

        var req = new CompactionRequest(conv, null, 100_000, MODEL);
        CompactionResult res = new ToolResultsCompactor().compact(req);

        assertEquals(2, res.messagesRewritten(), "older two tool results should be rewritten");
        assertEquals(0, res.messagesDropped());

        List<Message> out = res.conversation();
        // System + first user verbatim
        assertEquals(conv.get(0), out.get(0));
        assertEquals(conv.get(1), out.get(1));
        // Most recent assistant + most recent tool message verbatim
        assertEquals(conv.get(6), out.get(6));
        assertEquals(conv.get(7), out.get(7));

        // Older tool results turned into stubs
        Message.Tool stub1 = (Message.Tool) out.get(3);
        Message.Tool stub2 = (Message.Tool) out.get(5);
        assertTrue(stub1.content().startsWith("[compacted: fetch result, ~"), stub1.content());
        assertTrue(stub1.content().endsWith(" chars omitted]"));
        assertTrue(stub2.content().startsWith("[compacted: fetch result, ~"), stub2.content());

        assertTrue(res.approximateTokensFreed() > 0, "freed tokens should be > 0");
    }

    @Test
    void mostRecentAssistantAndToolMessageArePreservedByteIdentical() {
        var tc = new ToolCall("c1", "search", Map.of());
        var lastAssistant = new Message.Assistant("", List.of(tc));
        var lastTool = new Message.Tool("c1", large("RECENT-"));

        var oldTc = new ToolCall("c0", "search", Map.of());
        List<Message> conv = List.of(
                new Message.System("sys"),
                new Message.User("u"),
                new Message.Assistant("", List.of(oldTc)),
                new Message.Tool("c0", large("OLD-")),
                lastAssistant,
                lastTool);

        var req = new CompactionRequest(conv, null, 100_000, MODEL);
        CompactionResult res = new ToolResultsCompactor().compact(req);

        List<Message> out = res.conversation();
        assertSame(lastAssistant, out.get(4),
                "most recent assistant must be the same instance (byte-identical)");
        assertSame(lastTool, out.get(5),
                "most recent tool message must be the same instance (byte-identical)");
        assertEquals(1, res.messagesRewritten());
    }

    @Test
    void idempotent() {
        var tc1 = new ToolCall("c1", "fetch", Map.of());
        var tc2 = new ToolCall("c2", "fetch", Map.of());
        var tc3 = new ToolCall("c3", "fetch", Map.of());
        List<Message> conv = List.of(
                new Message.System("sys"),
                new Message.User("u"),
                new Message.Assistant("", List.of(tc1)),
                new Message.Tool("c1", large("A-")),
                new Message.Assistant("", List.of(tc2)),
                new Message.Tool("c2", large("B-")),
                new Message.Assistant("", List.of(tc3)),
                new Message.Tool("c3", large("C-")));

        var compactor = new ToolResultsCompactor();
        var once = compactor.compact(new CompactionRequest(conv, null, 100_000, MODEL));
        var twice = compactor.compact(new CompactionRequest(once.conversation(), null, 100_000, MODEL));

        assertEquals(once.conversation(), twice.conversation());
        assertEquals(0, twice.messagesRewritten(),
                "second pass should not rewrite anything");
    }

    @Test
    void stopsOnceTargetHeadroomIsMet() {
        var tc1 = new ToolCall("c1", "fetch", Map.of());
        var tc2 = new ToolCall("c2", "fetch", Map.of());
        var tc3 = new ToolCall("c3", "fetch", Map.of());
        List<Message> conv = List.of(
                new Message.System("sys"),
                new Message.User("u"),
                new Message.Assistant("", List.of(tc1)),
                new Message.Tool("c1", large("A-")),
                new Message.Assistant("", List.of(tc2)),
                new Message.Tool("c2", large("B-")),
                new Message.Assistant("", List.of(tc3)),
                new Message.Tool("c3", large("C-")));

        // Tiny target: rewriting one ~2000-char tool result frees ~500 tokens,
        // which exceeds 1, so we should stop after rewriting exactly 1.
        var req = new CompactionRequest(conv, null, 1, MODEL);
        CompactionResult res = new ToolResultsCompactor().compact(req);

        assertEquals(1, res.messagesRewritten());
    }

    @Test
    void unknownToolNameFallsBackGracefully() {
        // Tool message references an id that no preceding assistant turn knows about.
        List<Message> conv = List.of(
                new Message.System("sys"),
                new Message.User("u"),
                new Message.Assistant("nothing", List.of()),
                new Message.Tool("orphan", large("X-")),
                new Message.Assistant("done", List.of()));

        var req = new CompactionRequest(conv, null, 100_000, MODEL);
        CompactionResult res = new ToolResultsCompactor().compact(req);

        Message.Tool stub = (Message.Tool) res.conversation().get(3);
        assertTrue(stub.content().startsWith("[compacted: unknown result, ~"), stub.content());
    }

    @Test
    void smallToolResultsAreLeftAlone() {
        var tc = new ToolCall("c1", "tiny", Map.of());
        List<Message> conv = new ArrayList<>(List.of(
                new Message.System("sys"),
                new Message.User("u"),
                new Message.Assistant("", List.of(tc)),
                new Message.Tool("c1", "short"),
                new Message.Assistant("ok", List.of())));

        var req = new CompactionRequest(conv, null, 100_000, MODEL);
        CompactionResult res = new ToolResultsCompactor().compact(req);

        assertEquals(0, res.messagesRewritten());
    }
}
