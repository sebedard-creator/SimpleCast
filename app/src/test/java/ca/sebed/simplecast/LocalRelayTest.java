package ca.sebed.simplecast;

import fi.iki.elonen.NanoHTTPD;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class LocalRelayTest {
    private NanoHTTPD upstream;
    private LocalRelay relay;
    private String source;
    private final byte[] transport = SegmentDecoderTest.transport();
    private final AtomicInteger imageLoads = new AtomicInteger();
    private final AtomicReference<String> observedOrigin = new AtomicReference<>();
    private final AtomicReference<String> observedCookie = new AtomicReference<>();
    private final AtomicReference<String> observedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> originalAuthorization = new AtomicReference<>();
    private String redirect;
    @Before public void startUpstream() throws Exception {
        byte[] image = SegmentDecoderTest.pixelEnvelope(transport, 3, 4);
        upstream = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                String path = request.getUri();
                observedOrigin.set(request.getHeaders().get("origin"));
                observedCookie.set(request.getHeaders().get("cookie"));
                if (path.equals("/redirect.m3u8")) {
                    originalAuthorization.set(request.getHeaders().get("authorization"));
                    Response response = newFixedLengthResponse(code(302), "text/plain", "");
                    response.addHeader("Location", redirect); return response;
                }
                byte[] body; int status = 200; String mime = "application/vnd.apple.mpegurl";
                if (path.equals("/master.m3u8")) body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild/index.m3u8?sig=A%2FB\n".getBytes(StandardCharsets.UTF_8);
                else if (path.equals("/child/index.m3u8")) body = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXTINF:6,\n../wrapped.image?sig=C%2BD\n".getBytes(StandardCharsets.UTF_8);
                else if (path.equals("/wrapped.image")) { imageLoads.incrementAndGet(); body = image; mime = "image/png"; }
                else if (path.equals("/key.bin")) { body = new byte[16]; mime = "application/octet-stream"; }
                else if (path.equals("/denied.m3u8")) { body = "denied".getBytes(StandardCharsets.UTF_8); status = 403; }
                else { body = new byte[0]; status = 404; }
                return newFixedLengthResponse(code(status), mime, new java.io.ByteArrayInputStream(body), body.length);
            }
        };
        upstream.start(3000, true); source = "http://127.0.0.1:" + upstream.getListeningPort();
    }
    private static NanoHTTPD.Response.IStatus code(int value) {
        return new NanoHTTPD.Response.IStatus() {
            public int getRequestStatus() { return value; }
            public String getDescription() { return value + " Fixture"; }
        };
    }
    private String startRelay(String path) throws Exception {
        relay = new LocalRelay("127.0.0.1", source + path, "https://player.test/embed", Map.of("User-Agent", "Test WebView"), url -> "session=fixture");
        relay.startMedia(true); return relay.rootUrl();
    }
    private HttpURLConnection request(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); return connection;
    }
    private String text(String url) throws Exception {
        HttpURLConnection connection = request(url);
        try (var input = connection.getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
    }
    private String mediaLine(String playlist) { return playlist.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).findFirst().orElseThrow(); }
    @After public void stopServers() { if (relay != null) relay.closeMedia(); if (upstream != null) upstream.stop(); }

    @Test public void servesNestedHlsAndRestoresImageVideoWithBrowserContextAndCors() throws Exception {
        String master = text(startRelay("/master.m3u8"));
        String playlist = text(mediaLine(master)); String segment = mediaLine(playlist);
        assertTrue(playlist.contains("URI=\"http://127.0.0.1:")); assertFalse(playlist.contains("wrapped.image"));
        HttpURLConnection connection = request(segment);
        try (var body = connection.getInputStream()) {
            assertEquals("video/mp2t", connection.getContentType()); assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"));
            assertArrayEquals(transport, body.readAllBytes());
            assertEquals("https://player.test", observedOrigin.get()); assertEquals("session=fixture", observedCookie.get());
        } finally { connection.disconnect(); }
        text(segment); assertEquals(1, imageLoads.get());
    }
    @Test public void honorsByteRangesOnDecodedVideoNotOnTheImageEnvelope() throws Exception {
        String segment = mediaLine(text(mediaLine(text(startRelay("/master.m3u8")))));
        HttpURLConnection connection = request(segment); connection.setRequestProperty("Range", "bytes=188-375");
        try (var body = connection.getInputStream()) {
            assertEquals(206, connection.getResponseCode()); assertEquals("bytes 188-375/" + transport.length, connection.getHeaderField("Content-Range"));
            assertArrayEquals(java.util.Arrays.copyOfRange(transport, 188, 376), body.readAllBytes());
        } finally { connection.disconnect(); }
    }
    @Test public void refusesUnknownResourcesAndDoesNotExposeAnOpenProxy() throws Exception {
        String root = startRelay("/master.m3u8");
        HttpURLConnection unknown = request(root.replace("/index.m3u8", "/unknown/index.m3u8"));
        assertEquals(404, unknown.getResponseCode()); unknown.disconnect();
        HttpURLConnection wrongSession = request(root.replaceFirst("/([0-9a-f]{32})/", "/00000000000000000000000000000000/"));
        assertEquals(404, wrongSession.getResponseCode()); wrongSession.disconnect();
        assertTrue(text(root + "?url=http://unregistered.test/private").startsWith("#EXTM3U"));
        HttpURLConnection post = request(root); post.setRequestMethod("POST"); assertEquals(405, post.getResponseCode()); post.disconnect();
    }
    @Test public void reportsUpstreamFailuresInsteadOfReturningAnInvalidPlaylist() throws Exception {
        HttpURLConnection connection = request(startRelay("/denied.m3u8"));
        assertEquals(502, connection.getResponseCode()); assertTrue(relay.lastError().contains("403")); connection.disconnect();
    }
    @Test public void doesNotForwardAuthorizationAcrossRedirectOrigins() throws Exception {
        NanoHTTPD other = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                observedAuthorization.set(request.getHeaders().get("authorization"));
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-ENDLIST\n");
            }
        };
        other.start(3000, true); redirect = "http://127.0.0.1:" + other.getListeningPort() + "/result.m3u8";
        try {
            relay = new LocalRelay("127.0.0.1", source + "/redirect.m3u8", "https://player.test/", Map.of("Authorization", "Bearer fixture"), null);
            relay.startMedia(true); text(relay.rootUrl());
            assertEquals("Bearer fixture", originalAuthorization.get()); assertNull(observedAuthorization.get());
        } finally { other.stop(); }
    }
    @Test public void validatesSuffixOpenEndedAndInvalidRanges() {
        assertArrayEquals(new long[]{90,99}, LocalRelay.parseRange("bytes=-10",100));
        assertArrayEquals(new long[]{50,99}, LocalRelay.parseRange("bytes=50-",100));
        assertNull(LocalRelay.parseRange("bytes=100-",100)); assertNull(LocalRelay.parseRange("bytes=20-10",100));
        assertNull(LocalRelay.parseRange("bytes=0-5,10-20",100));
    }
}
