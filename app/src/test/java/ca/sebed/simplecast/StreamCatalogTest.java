package ca.sebed.simplecast;

import org.junit.Test;
import static org.junit.Assert.*;

public class StreamCatalogTest {
    @Test public void recognizesSignedUrlsWithoutMatchingTokensOrSegments() {
        assertEquals("application/x-mpegURL", StreamCatalog.mediaMime("https://cdn.test/live.M3U8?token=secret&expires=1", ""));
        assertNull(StreamCatalog.mediaMime("https://cdn.test/image?next=movie.mp4", "image/png"));
        assertNull(StreamCatalog.mediaMime("https://cdn.test/segment.ts", "video/mp2t"));
        assertNull(StreamCatalog.mediaMime("https://cdn.test/segment.m4s", ""));
    }
    @Test public void mimeDetectsExtensionlessManifestAndRedirectResult() {
        assertEquals("application/x-mpegURL", StreamCatalog.mediaMime("https://cdn.test/play?id=3", "application/vnd.apple.mpegurl; charset=utf-8"));
        assertEquals("application/dash+xml", StreamCatalog.mediaMime("https://cdn.test/play", "APPLICATION/DASH+XML"));
        assertEquals("video/mp4", StreamCatalog.mediaMime("https://cdn.test/asset/42", "video/mp4"));
    }
    @Test public void rejectsNonNetworkAndCredentialUrls() {
        for (String url : new String[]{"blob:https://site.test/uuid", "javascript:alert(1)", "file:///etc/passwd", "https://user:pass@site.test/video.mp4", "https:///video.mp4", "not a url"}) {
            assertFalse(url, StreamCatalog.validUrl(url));
            assertNull(StreamCatalog.mediaMime(url, "video/mp4"));
        }
    }
    @Test public void mergesMetadataWithoutLosingSignedUrlOrPlayerOwnership() {
        StreamCatalog catalog = new StreamCatalog();
        String url = "https://cdn.test/live.m3u8?token=A%2FB&expires=4";
        var stream = catalog.add(url, "", "https://player.test/embed", "", "Network", null);
        catalog.add(url, "", "https://player.test/embed", "video-1", "Actual title", true);
        catalog.add(url, "", "https://player.test/embed", "", "Network", null);
        assertEquals(1, catalog.size());
        assertEquals(url, stream.url);
        assertEquals("Actual title", stream.title);
        assertEquals(Boolean.TRUE, stream.live);
        assertTrue(stream.exactMatch("https://player.test/embed", "video-1"));
        assertFalse(stream.matches("https://unrelated.test/", "video-1"));
    }
    @Test public void sameUrlCanBelongToTwoPlayers() {
        StreamCatalog catalog = new StreamCatalog();
        var stream = catalog.add("https://cdn.test/video.mp4", "", "https://page.test/", "video-1", "One", false);
        catalog.add(stream.url, "", "https://page.test/", "video-2", "Two", false);
        assertTrue(stream.exactMatch("https://page.test/", "video-1"));
        assertTrue(stream.exactMatch("https://page.test/", "video-2"));
        assertFalse(stream.matches("https://page.test/", "video-3"));
    }
    @Test public void boundsCatalogAndKeepsUpdatingExistingStreams() {
        StreamCatalog catalog = new StreamCatalog();
        for (int i = 0; i < 200; i++) catalog.add("https://cdn.test/" + i + ".mp4", "", "", "", "", null);
        assertEquals(150, catalog.size());
        assertNotNull(catalog.add("https://cdn.test/0.mp4", "", "", "", "", true));
        catalog.clear(); assertEquals(0, catalog.size());
    }
}
