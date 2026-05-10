package org.trybunal.tool.webfetch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class UrlGuardsTest {

    @Test
    void httpAndHttpsAccepted() {
        assertDoesNotThrow(() -> UrlGuards.assertHttp(URI.create("http://example.com/")));
        assertDoesNotThrow(() -> UrlGuards.assertHttp(URI.create("https://example.com/")));
    }

    @Test
    void nonHttpSchemesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertHttp(URI.create("file:///etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertHttp(URI.create("ftp://example.com/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertHttp(URI.create("gopher://example.com/")));
    }

    @Test
    void privateAndLoopbackHostsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://127.0.0.1/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://10.1.2.3/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://192.168.0.1/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://169.254.169.254/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://[::1]/")));
        assertThrows(IllegalArgumentException.class,
                () -> UrlGuards.assertPublicHost(URI.create("http://localhost/")));
    }

    @Test
    void publicHostsAccepted() {
        assertDoesNotThrow(() -> UrlGuards.assertPublicHost(URI.create("http://example.com/")));
        assertDoesNotThrow(() -> UrlGuards.assertPublicHost(URI.create("http://1.1.1.1/")));
    }
}
