package ca.sebed.simplecast;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class PlaybackContextTest {
    @Test public void derivesSiteContextAndNeverCopiesBrowserCredentials() {
        var headers = PlaybackContext.headers("https://player.test:8443/embed/5", Map.of(
            "Cookie", "private", "Authorization", "Bearer private", "Host", "other.test"), "Browser/1");
        assertEquals(Map.of("Referer", "https://player.test:8443/embed/5", "Origin", "https://player.test:8443", "User-Agent", "Browser/1"), headers);
    }
    @Test public void prefersObservedHeadersAndRejectsHeaderInjection() {
        var headers = PlaybackContext.headers("https://fallback.test/", Map.of(
            "referer", "https://actual.test/", "origin", "https://actual.test", "user-agent", "bad\r\nInjected: yes"), "Browser/2");
        assertEquals("https://actual.test/", headers.get("Referer"));
        assertEquals("https://actual.test", headers.get("Origin"));
        assertEquals("Browser/2", headers.get("User-Agent"));
    }
    @Test public void acceptsCompleteSignedUrlsAndRejectsLocalSchemesAndUserInfo() {
        assertTrue(PlaybackContext.validUrl("https://cdn.test/live.m3u8?t=A%2FB%3D"));
        for (String value : new String[]{"file:///private", "blob:https://site.test/id", "https://user:pass@site.test/", "not a URL"})
            assertFalse(PlaybackContext.validUrl(value));
    }
    @Test public void forwardsManifestTokenOnlyToSameOriginManifests() {
        String media = "https://cdn.test/live.m3u8?server=2";
        var headers = PlaybackContext.headers("https://player.test/", Map.of("x-token", "temporary-token"), "Browser/1");
        assertEquals("temporary-token", headers.get("X-Token"));
        assertEquals("temporary-token", PlaybackContext.manifestToken(media, media, headers));
        assertEquals("temporary-token", PlaybackContext.manifestToken(media, "https://CDN.test:443/variant.m3u8", headers));
        for (String request : new String[]{"https://other.test/live.m3u8", "https://cdn.test:444/live.m3u8",
            "http://cdn.test/live.m3u8", "https://cdn.test/segment.ts", "https://cdn.test/key", "file:///live.m3u8"})
            assertNull(request, PlaybackContext.manifestToken(media, request, headers));
        assertFalse(PlaybackContext.headers("", Map.of("X-Token", "bad\r\nvalue"), "Browser/1").containsKey("X-Token"));
    }
}
