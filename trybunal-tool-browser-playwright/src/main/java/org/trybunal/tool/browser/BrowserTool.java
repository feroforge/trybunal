package org.trybunal.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.trybunal.api.spi.Tool;
import org.trybunal.api.tool.Citation;
import org.trybunal.api.tool.Source;
import org.trybunal.api.tool.ToolResult;
import org.trybunal.api.tool.ToolSpec;

/**
 * Renders a URL with a headless Chromium browser and returns the visible text plus
 * a screenshot artifact.
 *
 * <p>Used as a fallback when {@code web_fetch} reports that a page is JS-heavy or
 * returns too little content. Applies the same SSRF policy as {@code web_fetch}
 * (only http/https, only public addresses) without depending on that module.</p>
 *
 * <p>Stateless; the underlying {@link BrowserSession} is process-shared and lazy.</p>
 */
public final class BrowserTool implements Tool {

    private static final String NAME = "web_browser";
    private static final int DEFAULT_WAIT_MS = 2_000;
    private static final int DEFAULT_MAX_CHARS = 40_000;
    private static final int SCHEMA_MIN_CHARS = 256;
    private static final int SCHEMA_MAX_CHARS = 200_000;
    private static final int MAX_WAIT_MS = 30_000;
    private static final int NAVIGATE_TIMEOUT_MS = 30_000;
    private static final int SELECTOR_TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = "trybunal-browser/0.1";

    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Render a URL with a headless browser. Use when web_fetch reported the page as "
                    + "JS-heavy or returned too little content. Waits for network idle and the "
                    + "optional CSS selector before extracting text.",
            buildSchema());

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public BrowserTool() {}

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolResult invoke(Map<String, Object> arguments) {
        Object urlArg = arguments == null ? null : arguments.get("url");
        if (urlArg == null || urlArg.toString().isBlank()) {
            return ToolResult.error("Missing required argument: url");
        }

        URI uri;
        try {
            uri = URI.create(urlArg.toString().strip());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid url: " + e.getMessage());
        }

        try {
            assertHttp(uri);
            assertPublicHost(uri);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Refused: " + e.getMessage());
        }

        String waitSelector = null;
        Object ws = arguments.get("wait_selector");
        if (ws != null && !ws.toString().isBlank()) {
            waitSelector = ws.toString().strip();
        }

        int waitMs = DEFAULT_WAIT_MS;
        Object wm = arguments.get("wait_ms");
        if (wm != null) {
            try {
                waitMs = ((Number) wm).intValue();
            } catch (ClassCastException ignored) {
                try { waitMs = Integer.parseInt(wm.toString()); } catch (NumberFormatException ignored2) { /* use default */ }
            }
            if (waitMs < 0) waitMs = 0;
            if (waitMs > MAX_WAIT_MS) waitMs = MAX_WAIT_MS;
        }

        int maxChars = DEFAULT_MAX_CHARS;
        Object mc = arguments.get("max_chars");
        if (mc != null) {
            try {
                maxChars = ((Number) mc).intValue();
            } catch (ClassCastException ignored) {
                try { maxChars = Integer.parseInt(mc.toString()); } catch (NumberFormatException ignored2) { /* use default */ }
            }
            if (maxChars < SCHEMA_MIN_CHARS) maxChars = SCHEMA_MIN_CHARS;
            if (maxChars > SCHEMA_MAX_CHARS) maxChars = SCHEMA_MAX_CHARS;
        }

        BrowserSession session;
        try {
            session = BrowserSession.shared();
        } catch (IllegalStateException e) {
            return ToolResult.error("Browser unavailable: " + e.getMessage()
                    + " — run: npx playwright install chromium");
        }

        Browser.NewContextOptions ctxOptions = new Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setViewportSize(1280, 900)
                .setJavaScriptEnabled(true)
                .setAcceptDownloads(false);

        try (BrowserContext context = session.browser().newContext(ctxOptions)) {
            Page page = context.newPage();

            try {
                page.navigate(uri.toString(),
                        new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.NETWORKIDLE)
                                .setTimeout(NAVIGATE_TIMEOUT_MS));
            } catch (com.microsoft.playwright.TimeoutError e) {
                return ToolResult.error("Navigation timeout for " + uri + ": " + e.getMessage());
            }

            if (waitSelector != null) {
                try {
                    page.waitForSelector(waitSelector,
                            new Page.WaitForSelectorOptions().setTimeout(SELECTOR_TIMEOUT_MS));
                } catch (com.microsoft.playwright.TimeoutError e) {
                    return ToolResult.error("Selector timeout for '" + waitSelector + "': " + e.getMessage());
                }
            }

            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("Interrupted during wait");
                }
            }

            String title = page.title();
            if (title == null) title = "";

            String bodyText;
            try {
                bodyText = page.innerText("body");
            } catch (Exception e) {
                bodyText = "";
            }

            byte[] textBytes = bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String sha = sha256Hex(textBytes);

            Path screenshotPath = screenshotPath(sha);
            try {
                Files.createDirectories(screenshotPath.getParent());
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
            } catch (IOException e) {
                return ToolResult.error("Screenshot save failed: " + e.getMessage());
            } catch (Exception e) {
                return ToolResult.error("Screenshot failed: " + e.getMessage());
            }

            if (bodyText.length() > maxChars) {
                bodyText = bodyText.substring(0, maxChars);
            }

            Source source = new Source(uri, title, Instant.now(), sha, Optional.of(screenshotPath));
            Citation citation = new Citation(source, bodyText, 0, bodyText.length());
            return new ToolResult(bodyText, false, List.of(citation));
        } catch (Exception e) {
            return ToolResult.error("Browser error: " + e.getMessage());
        }
    }

    private static Path screenshotPath(String sha) {
        return Path.of(System.getProperty("java.io.tmpdir"), "trybunal-browser", sha + ".png");
    }

    // -------------------------------------------------------------------------
    // SSRF guard (rules lifted from web-fetch; source NOT shared)
    // -------------------------------------------------------------------------

    private static void assertHttp(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new IllegalArgumentException("uri scheme required");
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }
    }

    private static void assertPublicHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("uri host required");
        }
        String stripped = host;
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        String lower = stripped.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw new IllegalArgumentException("private host: " + host);
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(stripped);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("unknown host: " + host, e);
        }
        for (InetAddress addr : addrs) {
            assertPublicAddress(addr, host);
        }
    }

    private static void assertPublicAddress(InetAddress addr, String host) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()) {
            throw new IllegalArgumentException("private host: " + host + " -> " + addr.getHostAddress());
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 169 && b1 == 254) {
                throw new IllegalArgumentException("link-local host: " + host);
            }
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                throw new IllegalArgumentException("CGN host: " + host);
            }
            if (b0 == 0) {
                throw new IllegalArgumentException("reserved host: " + host);
            }
        }
    }

    // -------------------------------------------------------------------------

    private static String sha256Hex(byte[] bytes) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("format", "uri");

        Map<String, Object> waitSelectorProp = new LinkedHashMap<>();
        waitSelectorProp.put("type", "string");

        Map<String, Object> waitMsProp = new LinkedHashMap<>();
        waitMsProp.put("type", "integer");
        waitMsProp.put("minimum", 0);
        waitMsProp.put("maximum", MAX_WAIT_MS);
        waitMsProp.put("default", DEFAULT_WAIT_MS);

        Map<String, Object> maxCharsProp = new LinkedHashMap<>();
        maxCharsProp.put("type", "integer");
        maxCharsProp.put("minimum", SCHEMA_MIN_CHARS);
        maxCharsProp.put("maximum", SCHEMA_MAX_CHARS);
        maxCharsProp.put("default", DEFAULT_MAX_CHARS);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", urlProp);
        properties.put("wait_selector", waitSelectorProp);
        properties.put("wait_ms", waitMsProp);
        properties.put("max_chars", maxCharsProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }
}
