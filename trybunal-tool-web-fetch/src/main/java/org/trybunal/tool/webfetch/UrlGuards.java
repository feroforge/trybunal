package org.trybunal.tool.webfetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Pure utility for refusing dangerous URLs before they hit the network.
 *
 * <p>Used by {@link WebFetchTool#invoke(java.util.Map)} to enforce a strict
 * SSRF policy: only http/https schemes, only public addresses.</p>
 */
final class UrlGuards {

    private UrlGuards() {}

    /** Throws {@link IllegalArgumentException} unless {@code uri} is http or https. */
    static void assertHttp(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new IllegalArgumentException("uri scheme required");
        }
        String scheme = uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }
    }

    /**
     * Resolves {@code uri.getHost()} via {@link InetAddress#getAllByName(String)}
     * and throws if ANY resolved address is a loopback, link-local, site-local,
     * any-local, or multicast address. Also rejects literal IP hosts that match
     * RFC1918 ranges before resolution.
     */
    static void assertPublicHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("uri host required");
        }
        // Strip IPv6 brackets if present
        String stripped = host;
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        // Reject obvious string-form locals quickly.
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
            // 169.254.0.0/16 link-local (covered above for resolved addrs but defend)
            if (b0 == 169 && b1 == 254) {
                throw new IllegalArgumentException("link-local host: " + host);
            }
            // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                throw new IllegalArgumentException("CGN host: " + host);
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                throw new IllegalArgumentException("reserved host: " + host);
            }
        }
    }
}
