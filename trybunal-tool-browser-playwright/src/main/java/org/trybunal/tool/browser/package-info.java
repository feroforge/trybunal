/**
 * Headless-browser tool that renders JS-heavy pages via Playwright/Chromium.
 *
 * <p>Registered as a {@link org.trybunal.api.spi.Tool} SPI under the name
 * {@code web_browser}. The entry point is {@link org.trybunal.tool.browser.BrowserTool};
 * the underlying Playwright/Browser lifecycle is managed by the lazy singleton
 * {@link org.trybunal.tool.browser.BrowserSession}.</p>
 */
package org.trybunal.tool.browser;
