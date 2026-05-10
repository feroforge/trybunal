package org.trybunal.tool.download;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxTest {

    @TempDir
    Path tmp;

    @Test
    void rejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, "../etc/passwd"));
    }

    @Test
    void rejectsAbsolutePath() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, "/abs"));
    }

    @Test
    void rejectsSeparatorInName() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, "foo/bar"));
    }

    @Test
    void sanitisesWeirdName() {
        Path resolved = Sandbox.resolveSafeChild(tmp, "weird name?.PDF");
        String name = resolved.getFileName().toString();
        // Special chars replaced by '_', extension preserved
        assertTrue(name.matches("[A-Za-z0-9._\\-]+"), "name should be sanitised: " + name);
        assertTrue(name.endsWith(".PDF"), "extension should be preserved: " + name);
        assertTrue(resolved.startsWith(tmp), "must be under sandbox root");
    }

    @Test
    void rejectsEmptyName() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, ""));
    }

    @Test
    void rejectsDot() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, "."));
    }

    @Test
    void rejectsDotDot() {
        assertThrows(IllegalArgumentException.class,
                () -> Sandbox.resolveSafeChild(tmp, ".."));
    }

    @Test
    void appendsBinWhenNoExtension() {
        Path resolved = Sandbox.resolveSafeChild(tmp, "myfile");
        assertTrue(resolved.getFileName().toString().endsWith(".bin"),
                "should append .bin when no extension");
    }
}
