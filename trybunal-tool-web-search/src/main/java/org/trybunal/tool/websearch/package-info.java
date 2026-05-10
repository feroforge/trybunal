/**
 * DuckDuckGo HTML web-search tool.
 *
 * <p>Provides {@link org.trybunal.tool.websearch.WebSearchTool}, a stateless
 * {@link org.trybunal.api.spi.Tool} implementation that queries
 * {@code https://html.duckduckgo.com/html/} and returns ranked result entries.
 * The implementation is registered for {@link java.util.ServiceLoader} discovery
 * under {@code META-INF/services/org.trybunal.api.spi.Tool}.</p>
 */
package org.trybunal.tool.websearch;
