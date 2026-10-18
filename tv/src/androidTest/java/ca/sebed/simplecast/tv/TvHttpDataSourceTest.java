package ca.sebed.simplecast.tv;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real network requests through the TV transport, without a streaming-site dependency. */
@RunWith(AndroidJUnit4.class)
public class TvHttpDataSourceTest {
    @Test public void preservesContextAndResolvedUrlAcrossRedirects() throws Exception {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        NanoHTTPD fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                if (request.getUri().equals("/start")) {
                    Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", "/final.m3u8"); return response;
                }
                observed.set(request.getHeaders());
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n");
            }
        };
        fixture.start(5000, true);
        DataSource source = source(Map.of("Referer", "https://player.test/", "Origin", "https://player.test", "User-Agent", "Browser/1"));
        try {
            source.open(new DataSpec.Builder().setUri("http://127.0.0.1:" + fixture.getListeningPort() + "/start").build());
            byte[] bytes = new byte[8]; assertEquals(8, source.read(bytes, 0, 8));
            assertEquals("#EXTM3U\n", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(source.getUri().toString().endsWith("/final.m3u8"));
            assertEquals("https://player.test/", observed.get().get("referer"));
            assertEquals("https://player.test", observed.get().get("origin"));
            assertEquals("Browser/1", observed.get().get("user-agent"));
        } finally { source.close(); fixture.stop(); }
    }
    @Test public void preservesHttpStatusAndCanReopenAfterFailure() throws Exception {
        NanoHTTPD fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                return request.getUri().equals("/denied")
                    ? newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Denied")
                    : newFixedLengthResponse("OK");
            }
        };
        fixture.start(5000, true); DataSource source = source(Map.of());
        String base = "http://127.0.0.1:" + fixture.getListeningPort();
        try {
            try { source.open(new DataSpec.Builder().setUri(base + "/denied").build()); fail("Expected HTTP 403"); }
            catch (HttpDataSource.InvalidResponseCodeException error) { assertEquals(403, error.responseCode); }
            finally { source.close(); }
            source.open(new DataSpec.Builder().setUri(base + "/ok").build());
            byte[] bytes = new byte[2]; assertEquals(2, source.read(bytes, 0, 2));
            assertEquals("OK", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } finally { source.close(); fixture.stop(); }
    }
    @Test public void preservesByteRangeRequests() throws Exception {
        AtomicReference<String> range = new AtomicReference<>();
        NanoHTTPD fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                range.set(request.getHeaders().get("range")); byte[] payload = new byte[]{2, 3, 4, 5};
                Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", new ByteArrayInputStream(payload), payload.length);
                response.addHeader("Content-Range", "bytes 2-5/10"); return response;
            }
        };
        fixture.start(5000, true); DataSource source = source(Map.of());
        try {
            assertEquals(4, source.open(new DataSpec.Builder().setUri("http://127.0.0.1:" + fixture.getListeningPort() + "/video.mp4").setPosition(2).setLength(4).build()));
            byte[] bytes = new byte[4]; assertEquals(4, source.read(bytes, 0, 4));
            assertArrayEquals(new byte[]{2, 3, 4, 5}, bytes); assertEquals("bytes=2-5", range.get());
        } finally { source.close(); fixture.stop(); }
    }
    private DataSource source(Map<String, String> headers) {
        return TvHttpDataSource.factory(InstrumentationRegistry.getInstrumentation().getTargetContext(), headers).createDataSource();
    }
}
