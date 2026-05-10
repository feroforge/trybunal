package org.trybunal.tool.download;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Static utilities for the download sandbox.
 *
 * <p>The sandbox root is a single directory into which all downloaded files are
 * written. No file may escape that directory — {@link #resolveSafeChild} enforces
 * this at the path level before any I/O occurs.</p>
 */
public final class Sandbox {

    private static final String PROP = "trybunal.download.dir";
    private static final String DEFAULT_DIR = "trybunal-downloads";

    private Sandbox() {}

    /**
     * Returns the sandbox root, creating it on first call.
     *
     * <p>Override via system property {@code trybunal.download.dir}; default
     * is {@code <java.io.tmpdir>/trybunal-downloads}. On POSIX the directory is
     * created with {@code 0700} permissions.</p>
     */
    public static Path root() {
        String prop = System.getProperty(PROP);
        Path dir = (prop != null && !prop.isBlank())
                ? Paths.get(prop)
                : Paths.get(System.getProperty("java.io.tmpdir"), DEFAULT_DIR);
        if (!Files.exists(dir)) {
            try {
                if (isPosix()) {
                    Files.createDirectories(dir,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(dir);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create sandbox: " + dir, e);
            }
        }
        return dir;
    }

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Resolves {@code candidate} against {@code root} after sanitising the name.
     *
     * <p>Rejects path traversal, absolute paths, and any name containing {@code /}
     * or {@code \\}. The filename is sanitised: keep {@code [A-Za-z0-9._-]},
     * collapse other runs to {@code '_'}, truncate to 200 chars, append
     * {@code ".bin"} if no extension remains. The resolved path is verified to be
     * directly inside {@code root}.</p>
     *
     * @param root      sandbox directory
     * @param candidate raw filename from the caller
     * @return resolved path guaranteed to be a direct child of {@code root}
     * @throws IllegalArgumentException if the candidate is blank, {@code "."}, {@code ".."},
     *                                  absolute, or would escape the sandbox after sanitisation
     */
    public static Path resolveSafeChild(Path root, String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.equals(".") || candidate.equals("..")) {
            throw new IllegalArgumentException("invalid filename: " + candidate);
        }
        if (candidate.startsWith("/") || candidate.startsWith("\\") || Paths.get(candidate).isAbsolute()) {
            throw new IllegalArgumentException("absolute path rejected: " + candidate);
        }
        if (candidate.contains("/") || candidate.contains("\\")) {
            throw new IllegalArgumentException("path separator rejected in: " + candidate);
        }
        // Sanitise: keep [A-Za-z0-9._-], collapse other runs to '_'
        String sanitised = candidate.replaceAll("[^A-Za-z0-9._\\-]+", "_");
        // Truncate to 200 chars
        if (sanitised.length() > 200) {
            sanitised = sanitised.substring(0, 200);
        }
        // Append ".bin" if no extension
        if (!sanitised.contains(".")) {
            sanitised = sanitised + ".bin";
        }
        Path resolved = root.resolve(sanitised).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("path traversal detected: " + candidate);
        }
        // Must be a direct child (no subdirectory)
        if (!resolved.getParent().normalize().equals(root.normalize())) {
            throw new IllegalArgumentException("not a direct child of sandbox: " + candidate);
        }
        return resolved;
    }
}
