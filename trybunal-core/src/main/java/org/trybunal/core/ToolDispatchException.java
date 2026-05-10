package org.trybunal.core;

/**
 * Thrown by {@link ToolCallingHarness} only for unrecoverable conditions
 * (e.g. the model called a tool not registered AND no harness fallback
 * applies). Tool-side failures are NOT exceptions — they are
 * {@link org.trybunal.api.tool.ToolResult#error(String)} values.
 */
public final class ToolDispatchException extends RuntimeException {
    public ToolDispatchException(String message) { super(message); }
    public ToolDispatchException(String message, Throwable cause) { super(message, cause); }
}
