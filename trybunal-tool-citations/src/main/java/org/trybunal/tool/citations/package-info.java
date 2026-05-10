/**
 * In-memory citation store and report exporter for the Trybunal agent loop.
 *
 * <p>The three public types in this package form a self-contained unit:</p>
 * <ul>
 *   <li>{@link org.trybunal.tool.citations.CitationStore} — thread-safe, append-only store.</li>
 *   <li>{@link org.trybunal.tool.citations.CiteTool} — {@link org.trybunal.api.spi.Tool} SPI
 *       implementation the model calls to record an excerpt.</li>
 *   <li>{@link org.trybunal.tool.citations.CitationReport} — pure markdown formatter.</li>
 * </ul>
 *
 * <p>Module dependencies: {@code :trybunal-api} only. No filesystem or HTTP code.</p>
 */
package org.trybunal.tool.citations;
