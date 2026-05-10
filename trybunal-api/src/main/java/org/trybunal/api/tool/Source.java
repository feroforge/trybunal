package org.trybunal.api.tool;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * A retrievable, attributable origin for a piece of content.
 *
 * @param uri          canonical URL the content was fetched from; never null
 * @param title        human-readable title (e.g. HTML &lt;title&gt; or filename); may be blank;
 *                     null is coerced to {@code ""}
 * @param retrievedAt  wall-clock instant when the content was retrieved; never null
 * @param sha256       lowercase hex SHA-256 of the bytes that were retrieved; non-null, length 64
 * @param localPath    optional path under the session sandbox if the content was saved locally;
 *                     null is coerced to {@link Optional#empty()}
 */
public record Source(URI uri, String title, Instant retrievedAt, String sha256, Optional<Path> localPath) {
    public Source {
        if (uri == null) throw new IllegalArgumentException("uri required");
        if (title == null) title = "";
        if (retrievedAt == null) throw new IllegalArgumentException("retrievedAt required");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex chars");
        if (localPath == null) localPath = Optional.empty();
    }
}
