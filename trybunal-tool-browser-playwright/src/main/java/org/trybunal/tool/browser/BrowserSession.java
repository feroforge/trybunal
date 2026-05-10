package org.trybunal.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

/**
 * Process-wide holder for the shared {@link Playwright} and {@link Browser} instances.
 *
 * <p>Start-up is deferred until the first call to {@link #shared()} so that processes
 * that never invoke {@code web_browser} pay no initialization cost. The singleton is
 * internally synchronized; individual {@link com.microsoft.playwright.Page}s must be
 * created per call because Playwright pages are not thread-safe.</p>
 *
 * <p>A JVM shutdown hook closes the browser on normal exit. Tests may inject a
 * replacement via {@link #BrowserSession(Playwright, Browser)}.</p>
 */
public final class BrowserSession implements AutoCloseable {

    private static volatile BrowserSession INSTANCE;
    private static final Object LOCK = new Object();

    private final Playwright playwright;
    private final Browser browser;

    /** Package-private injection constructor for tests. */
    BrowserSession(Playwright playwright, Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    private BrowserSession() {
        Playwright pw;
        try {
            pw = Playwright.create();
        } catch (Exception e) {
            System.err.println("[trybunal] Browser launch failed. Run: npx playwright install chromium");
            throw new IllegalStateException("Playwright init failed — install Chromium first", e);
        }
        Browser br;
        try {
            br = pw.chromium().launch();
        } catch (Exception e) {
            pw.close();
            System.err.println("[trybunal] Browser launch failed. Run: npx playwright install chromium");
            throw new IllegalStateException("Chromium launch failed — install Chromium first", e);
        }
        this.playwright = pw;
        this.browser = br;
    }

    /**
     * Returns the process-wide singleton, starting Playwright on first call.
     *
     * @throws IllegalStateException if Chromium is not installed
     */
    public static BrowserSession shared() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    BrowserSession session = new BrowserSession();
                    Runtime.getRuntime().addShutdownHook(new Thread(session::closeQuietly, "trybunal-browser-shutdown"));
                    INSTANCE = session;
                }
            }
        }
        return INSTANCE;
    }

    /** Returns the underlying {@link Browser} for creating contexts. */
    public Browser browser() {
        return browser;
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) { /* best-effort shutdown */ }
    }
}
