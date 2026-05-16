package org.trybunal.api.spi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.trybunal.api.model.ContextWindow;
import org.trybunal.api.model.Message;
import org.trybunal.api.model.ModelId;

class CompactionRequestTest {

    private static final ModelId MODEL = new ModelId("test", "stub");

    @Test
    void rejectsNullConversation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionRequest(null, null, 100, MODEL));
    }

    @Test
    void rejectsNullModelId() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionRequest(List.of(), null, 100, null));
    }

    @Test
    void rejectsNegativeTargetHeadroom() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionRequest(List.of(), null, -1, MODEL));
    }

    @Test
    void zeroTargetHeadroomIsAllowed() {
        var req = new CompactionRequest(List.of(), null, 0, MODEL);
        assertEquals(0, req.targetHeadroomTokens());
    }

    @Test
    void currentWindowMayBeNull() {
        var req = new CompactionRequest(List.of(new Message.User("hi")), null, 100, MODEL);
        assertNull(req.currentWindow());
    }

    @Test
    void currentWindowIsPreservedWhenSet() {
        var cw = new ContextWindow(3000, 4096);
        var req = new CompactionRequest(List.of(new Message.User("hi")), cw, 100, MODEL);
        assertSame(cw, req.currentWindow());
    }

    @Test
    void defensivelyCopiesConversation() {
        List<Message> mutable = new ArrayList<>();
        mutable.add(new Message.User("first"));
        var req = new CompactionRequest(mutable, null, 100, MODEL);

        mutable.add(new Message.User("second"));

        assertEquals(1, req.conversation().size(),
                "request must not see mutations of the caller's list");
    }

    @Test
    void returnedConversationIsUnmodifiable() {
        var req = new CompactionRequest(List.of(new Message.User("hi")), null, 100, MODEL);
        assertThrows(UnsupportedOperationException.class,
                () -> req.conversation().add(new Message.User("x")));
    }
}
