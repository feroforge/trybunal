package org.trybunal.tool.mocks;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Static, deterministic fixture text used by the mock tools.
 *
 * <p><b>Contract.</b> Every method is a pure function of its arguments:
 * same arguments in always yield the same string out. No clocks, no
 * RNG, no I/O, no system-property reads. The shapes are intentionally
 * thin (~2 000 chars per fetch) so the upcoming compaction work in
 * Phase 5 Task 03 has room to grow conversations naturally.</p>
 *
 * <p>The fixtures are arranged to support a "research a stock"
 * workflow without rewriting prompts:</p>
 * <ul>
 *   <li>{@code web_search("…ticker AAPL…")} → hit #1 is an EDGAR index
 *       URL, hit #2 is the IR site, hit #3 is a generic news story.</li>
 *   <li>{@code web_fetch(EDGAR_URL)} → EDGAR-flavoured HTML excerpt
 *       containing a recognisable filing date.</li>
 *   <li>{@code safe_download(any_pdf_url)} → returns a fake local path
 *       under {@code build/mock-sandbox/} plus a SHA-256 derived
 *       deterministically from the URL.</li>
 * </ul>
 */
public final class MockFixtures {

    private MockFixtures() {}

    /** Fixed date stamped into fetch/browser bodies. Hard-coded, NOT now(). */
    private static final String FIXED_FILING_DATE = "2026-02-01";

    /** Three search hits per query, deterministic on (query, limit). */
    public static String searchResults(String query, int limit) {
        String q = query == null ? "" : query.strip();
        int n = Math.max(1, Math.min(3, limit));
        String edgarUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=0000320193&type=10-K";
        String irUrl    = "https://investor.apple.com/sec-filings/default.aspx";
        String newsUrl  = "https://example-news.test/markets/apple-10k-recap";

        StringBuilder sb = new StringBuilder();
        if (n >= 1) {
            sb.append("[1] EDGAR — Apple Inc. (AAPL) 10-K filings index\n");
            sb.append("    ").append(edgarUrl).append('\n');
            sb.append("    SEC EDGAR index of annual reports filed by Apple Inc., including the most recent 10-K. Query: ")
              .append(q).append('\n');
        }
        if (n >= 2) {
            sb.append("[2] Apple Investor Relations — SEC Filings\n");
            sb.append("    ").append(irUrl).append('\n');
            sb.append("    Apple's investor-facing mirror of SEC filings, press releases, and earnings transcripts.\n");
        }
        if (n >= 3) {
            sb.append("[3] Example News — Apple's latest 10-K, the takeaways\n");
            sb.append("    ").append(newsUrl).append('\n');
            sb.append("    Analyst recap of Apple's annual report covering services growth and capital returns.\n");
        }
        sb.append("(provider: mock)");
        return sb.toString();
    }

    /** Fetched text for a URL. Branches on host so EDGAR/IR/news read distinctly. */
    public static String fetchedText(URI url, int maxChars) {
        String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase();
        String body;
        if (host.contains("sec.gov")) {
            body = edgarBody(url);
        } else if (host.contains("investor.apple.com") || host.contains("apple.com")) {
            body = irBody(url);
        } else {
            body = genericNewsBody(url);
        }
        if (maxChars > 0 && body.length() > maxChars) {
            return body.substring(0, maxChars);
        }
        return body;
    }

    /** Browser-rendered text. Same content as the fetch fixture, marked. */
    public static String browserText(URI url, int maxChars) {
        String prefix = "[mock-browser] rendered ";
        String tail = fetchedText(url, Math.max(0, maxChars - prefix.length() - 64));
        String body = prefix + (url == null ? "<no url>" : url) + "\n\n" + tail;
        if (maxChars > 0 && body.length() > maxChars) {
            return body.substring(0, maxChars);
        }
        return body;
    }

    /** Fake on-disk path + deterministic SHA-256 for a saved file. */
    public static String savedFile(URI url, String hint) {
        String sha = sha256Of(url == null ? "" : url.toString());
        String name = (hint == null || hint.isBlank())
                ? defaultFilename(url)
                : hint.strip();
        String path = "build/mock-sandbox/" + sha.substring(0, 12) + "-" + sanitise(name);
        long bytes = (long) (sha.charAt(0) - '0' + 1) * 4096L;
        if (bytes < 4096L) bytes = 4096L;
        return "Saved " + bytes + " bytes to " + path + "; sha256=" + sha;
    }

    /** SHA-256 of an arbitrary string, lowercase hex. */
    public static String sha256Of(String s) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String edgarBody(URI url) {
        return """
                EDGAR Filing Index — Apple Inc. (CIK 0000320193)

                Filing type: 10-K (Annual Report)
                Filed: %DATE%
                Period of Report: 2025-09-27
                Accession Number: 0000320193-26-000010

                Documents

                Document   Description                      Type   Size
                aapl-10k.htm  10-K                          10-K   1.4 MB
                exhibit-21.htm List of Subsidiaries         EX-21  6 KB
                exhibit-31.htm CEO Certification            EX-31  12 KB

                Summary

                Apple Inc.'s Form 10-K for the fiscal year ended September 27, 2025
                was filed with the U.S. Securities and Exchange Commission on
                %DATE%. The filing reports total net sales of $397.1 billion,
                an increase of 2.1% versus the prior year, with services revenue
                of $96.5 billion. Product revenue was $300.6 billion, led by
                iPhone at $204.8 billion. Total operating expenses were $57.5
                billion. The report discusses ongoing investments in research
                and development, an effective tax rate of 15.2%, and capital
                return commitments. Management's Discussion and Analysis
                highlights supply-chain normalisation and growth in installed
                base of active devices across all geographic segments.

                This excerpt is a deterministic fixture served by the trybunal
                mock tool harness. It exists to exercise the agent loop
                without contacting sec.gov. Do not use the figures in this
                excerpt for real-world analysis.
                """.replace("%DATE%", FIXED_FILING_DATE)
                .replace("%URL%", url == null ? "" : url.toString());
    }

    private static String irBody(URI url) {
        return """
                Apple Investor Relations — SEC Filings

                Apple Inc. files annual (10-K), quarterly (10-Q), and current
                (8-K) reports with the U.S. Securities and Exchange Commission.
                The most recent 10-K was filed %DATE% for fiscal 2025. Earnings
                releases and conference-call transcripts are available under
                Quarterly Earnings.

                Recent filings
                  10-K     Annual report                    %DATE%
                  10-Q     Quarterly report (Q1 2026)       2026-01-25
                  8-K      Earnings release Q1 2026         2026-01-23
                  DEF 14A  Proxy statement                  2026-01-08

                Apple's annual shareholder meeting is scheduled for 2026-02-25.
                For the latest 10-K, see the EDGAR filing page linked above.
                This page is a deterministic mock-IR fixture and should not be
                relied upon for investment decisions.
                """.replace("%DATE%", FIXED_FILING_DATE)
                .replace("%URL%", url == null ? "" : url.toString());
    }

    private static String genericNewsBody(URI url) {
        return """
                Apple's latest 10-K, the takeaways

                Apple Inc. filed its annual report on %DATE%, capping a year
                of services-led growth and a normalising hardware cycle. Total
                revenue rose roughly 2% to $397 billion. Services crossed $96
                billion, lifting blended gross margin. Operating cash flow
                remained well above $110 billion, leaving the company with
                ample firepower for buybacks and dividends. The filing's risk
                factors are largely unchanged from last year: supply-chain
                concentration, regulatory scrutiny in the EU, and litigation
                surrounding the App Store. Management commentary emphasised
                continued investment in custom silicon and on-device AI
                features. This summary is a deterministic mock-news fixture
                generated by the trybunal mock tool harness for %URL%.
                """.replace("%DATE%", FIXED_FILING_DATE)
                .replace("%URL%", url == null ? "" : url.toString());
    }

    private static String defaultFilename(URI url) {
        if (url == null || url.getPath() == null || url.getPath().isBlank()) {
            return "download.bin";
        }
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
        if (last.isBlank()) return "download.bin";
        return last;
    }

    private static String sanitise(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
