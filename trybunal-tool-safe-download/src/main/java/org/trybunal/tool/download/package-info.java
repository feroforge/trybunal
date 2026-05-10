/**
 * Sandboxed file downloader tool for the Trybunal agent runtime.
 *
 * <p>Entry point is {@link org.trybunal.tool.download.SafeDownloadTool}, registered via
 * {@code META-INF/services/org.trybunal.api.spi.Tool}. {@link org.trybunal.tool.download.Sandbox}
 * provides the path-safety utilities.</p>
 */
package org.trybunal.tool.download;
