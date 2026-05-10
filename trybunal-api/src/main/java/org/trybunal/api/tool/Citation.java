package org.trybunal.api.tool;

/**
 * A specific span of {@code excerpt} attributed to {@link #source()}.
 *
 * <p>Offsets are character indices into {@code excerpt} (NOT into the original
 * source). They allow downstream renderers to highlight the cited span when
 * showing the excerpt verbatim.</p>
 *
 * @param source      where the excerpt came from; never null
 * @param excerpt     literal text quoted from the source; never null, may be empty
 * @param startOffset inclusive start in {@code excerpt}; &gt;= 0 and &lt;= excerpt.length()
 * @param endOffset   exclusive end in {@code excerpt}; &gt;= startOffset and &lt;= excerpt.length()
 */
public record Citation(Source source, String excerpt, int startOffset, int endOffset) {
    public Citation {
        if (source == null) throw new IllegalArgumentException("source required");
        if (excerpt == null) throw new IllegalArgumentException("excerpt required");
        if (startOffset < 0 || startOffset > excerpt.length())
            throw new IllegalArgumentException("startOffset out of range");
        if (endOffset < startOffset || endOffset > excerpt.length())
            throw new IllegalArgumentException("endOffset out of range");
    }
}
