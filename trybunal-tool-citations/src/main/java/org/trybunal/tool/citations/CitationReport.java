package org.trybunal.tool.citations;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;

/**
 * Pure formatter that renders a list of {@link Citation}s as a markdown bibliography.
 *
 * <p>Sources are deduplicated by URI; the most recent {@code retrievedAt} wins for the
 * header line. All excerpts for a given URI are listed under the same numbered entry.</p>
 *
 * <p>No I/O, no state. All methods are static.</p>
 */
public final class CitationReport {

    private CitationReport() {}

    /**
     * Renders {@code citations} as a markdown {@code ## Sources} section.
     *
     * <pre>
     * ## Sources
     * 1. [Title](https://...) — retrieved 2026-05-09T12:34:56Z, sha256:abcd...; saved to downloads/foo.pdf
     *    &gt; excerpt (truncated to 240 chars)
     * 2. ...
     * </pre>
     *
     * @param citations the citations to render; may be empty; never null
     * @return markdown string; never null
     */
    public static String renderMarkdown(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "## Sources\n\n_No sources recorded._\n";
        }

        // Deduplicate by URI, preserving insertion order of first occurrence.
        // Key: URI string; Value: [mostRecentSource, list-of-excerpts]
        Map<String, SourceEntry> byUri = new LinkedHashMap<>();
        for (Citation c : citations) {
            Source src = c.source();
            String key = src.uri().toString();
            SourceEntry entry = byUri.get(key);
            if (entry == null) {
                byUri.put(key, new SourceEntry(src, c.excerpt()));
            } else {
                // Most recent retrievedAt wins for the header.
                if (src.retrievedAt().isAfter(entry.source.retrievedAt())) {
                    entry.source = src;
                }
                entry.excerpts.add(c.excerpt());
            }
        }

        StringBuilder sb = new StringBuilder("## Sources\n\n");
        int index = 1;
        for (SourceEntry entry : byUri.values()) {
            Source src = entry.source;
            URI uri = src.uri();
            String title = src.title().isBlank() ? uri.toString() : src.title();
            sb.append(index).append(". [").append(title).append("](").append(uri).append(") — retrieved ")
              .append(src.retrievedAt()).append(", sha256:").append(src.sha256());
            src.localPath().ifPresent(p -> sb.append("; saved to ").append(p));
            sb.append('\n');
            for (String excerpt : entry.excerpts) {
                String truncated = excerpt.length() > 240 ? excerpt.substring(0, 240) + "…" : excerpt;
                sb.append("   > ").append(truncated).append('\n');
            }
            index++;
        }
        return sb.toString();
    }

    private static final class SourceEntry {
        Source source;
        final List<String> excerpts = new ArrayList<>();

        SourceEntry(Source source, String firstExcerpt) {
            this.source = source;
            this.excerpts.add(firstExcerpt);
        }
    }
}
