package org.trybunal.tool.websearch;

/**
 * A single search result, in the shape the {@code web_search} tool emits.
 *
 * <p>Package-private — internal to this module's provider strategy.</p>
 */
record SearchHit(String title, String url, String snippet) {}
