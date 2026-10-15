package ca.sebed.simplecast.tv;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import fi.iki.elonen.NanoHTTPD;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ManifestTokenDataSourceTest {
    @Test public void stripsTokenOnCrossOriginRedirectAndPreservesResolvedManifestUrl() throws Exception {
        AtomicReference<String> originalToken = new AtomicReference<>(), foreignToken = new AtomicReference<>("unrequested");
        NanoHTTPD destination = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                foreignToken.set(request.getHeaders().get("x-token"));
                return newFixedLengthResponse("#EXTM3U\n");
            }
        };
        destination.start(5000, true);
        String finalUrl = "http://127.0.0.1:" + destination.getListeningPort() + "/final.m3u8";
        NanoHTTPD origin = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                originalToken.set(request.getHeaders().get("x-token"));
                Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                response.addHeader("Location", finalUrl); return response;
            }
        };
        origin.start(5000, true);
        String mediaUrl = "http://127.0.0.1:" + origin.getListeningPort() + "/index.m3u8";
        var source = new ManifestTokenDataSource(new DefaultHttpDataSource.Factory().createDataSource(), mediaUrl,
            Map.of("X-Token", "private-stream-token"));
        try {
            assertEquals(8, source.open(new DataSpec.Builder().setUri(mediaUrl).build()));
            assertEquals("private-stream-token", originalToken.get()); assertNull(foreignToken.get());
            assertEquals(finalUrl, source.getUri().toString());
        } finally { source.close(); origin.stop(); destination.stop(); }
    }
}
