package ca.sebed.simplecast;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HlsPlaylistTest {
    @Test public void resolvesSignedNestedPlaylistsKeysAndSegmentsWithoutChangingTokens() throws Exception {
        List<String> resources = new ArrayList<>();
        String playlist = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/list.m3u8?sig=A%2FB\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2000\n../video/master.m3u8?sig=C%2BD\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"//keys.test/key?id=42\"\n#EXT-X-MAP:URI=\"init.mp4\"\n"
            + "#EXTINF:6,\nhttps://cdn.test/video.image?token=x%2Fy\n";
        String rewritten = HlsPlaylist.rewrite(playlist, "https://origin.test/live/index.m3u8", (url, kind) -> {
            resources.add(kind + " " + url); return "http://192.168.1.2/r/" + resources.size();
        });
        assertEquals(List.of("PLAYLIST https://origin.test/live/audio/list.m3u8?sig=A%2FB",
            "PLAYLIST https://origin.test/video/master.m3u8?sig=C%2BD", "KEY https://keys.test/key?id=42",
            "INIT https://origin.test/live/init.mp4", "SEGMENT https://cdn.test/video.image?token=x%2Fy"), resources);
        assertFalse(rewritten.contains("https://")); assertTrue(rewritten.contains("#EXT-X-STREAM-INF:BANDWIDTH=2000"));
    }
    @Test public void rewritesLowLatencyPartsAndRenditionReports() throws Exception {
        List<HlsPlaylist.Kind> kinds = new ArrayList<>();
        HlsPlaylist.rewrite("#EXTM3U\n#EXT-X-PART:DURATION=0.5,URI=\"part.ts\"\n#EXT-X-PRELOAD-HINT:TYPE=MAP,URI=\"init.mp4\"\n#EXT-X-RENDITION-REPORT:URI=\"other.m3u8\"\n",
            "https://origin.test/index.m3u8", (url, kind) -> { kinds.add(kind); return "http://local.test/item"; });
        assertEquals(List.of(HlsPlaylist.Kind.SEGMENT, HlsPlaylist.Kind.INIT, HlsPlaylist.Kind.PLAYLIST), kinds);
    }
    @Test public void rejectsHtmlAndNonHttpResources() {
        assertThrows(IOException.class, () -> HlsPlaylist.rewrite("<html>error</html>", "https://origin.test/", (url, kind) -> url));
        assertThrows(IOException.class, () -> HlsPlaylist.rewrite("#EXTM3U\nfile:///private/video.ts\n", "https://origin.test/", (url, kind) -> url));
    }
}
