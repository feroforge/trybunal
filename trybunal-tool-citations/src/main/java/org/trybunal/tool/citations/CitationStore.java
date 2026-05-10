package org.trybunal.tool.citations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.trybunal.api.tool.Citation;

/**
 * Thread-safe, append-only in-memory store for {@link Citation} instances accumulated
 * during an agent loop.
 *
 * <p><b>Contract.</b> All mutating operations are safe for concurrent use from
 * virtual threads. {@link #snapshot()} returns a defensive copy so callers may
 * iterate freely.</p>
 *
 * <p>A process-wide singleton ({@link #shared()}) is provided for convenience in
 * the agent loop; tests MUST use the no-arg constructor to avoid cross-test
 * pollution.</p>
 */
public final class CitationStore {

    private final List<Citation> all = Collections.synchronizedList(new ArrayList<>());

    private static final CitationStore SHARED = new CitationStore();

    /** Returns the process-wide shared instance. */
    public static CitationStore shared() { return SHARED; }

    /** Appends {@code c} to the store. Never null. */
    public void add(Citation c) { all.add(Objects.requireNonNull(c, "citation required")); }

    /** Returns a snapshot of all citations in insertion order. */
    public List<Citation> snapshot() { synchronized (all) { return List.copyOf(all); } }

    /** Removes all citations from the store. */
    public void clear() { synchronized (all) { all.clear(); } }
}
