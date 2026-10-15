package ca.sebed.simplecast.tv;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real TV decoding and HTTP control; uses only a local synthetic video fixture. */
@RunWith(AndroidJUnit4.class)
public class NativePlaybackTest {
    @Rule public ActivityTestRule<TvActivity> activity = new ActivityTestRule<>(TvActivity.class);
    private ControlServer receiver() throws Exception {
        Field field = TvActivity.class.getDeclaredField("server"); field.setAccessible(true);
        return (ControlServer) field.get(activity.getActivity());
    }
    private JSONObject request(String command, JSONObject body, String token, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + receiver().getListeningPort() + "/" + command).openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (token != null) connection.setRequestProperty("X-SimpleCast-Token", token);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            assertEquals(expected, connection.getResponseCode());
            try (var input = expected == 200 ? connection.getInputStream() : connection.getErrorStream(); var out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private String pair() throws Exception { return request("pair", new JSONObject().put("pin", receiver().pin()), null, 200).getString("token"); }
    @Test public void requiresPairingAndRejectsInvalidMediaUrls() throws Exception {
        request("status", new JSONObject(), null, 403);
        String token = pair();
        assertEquals("idle", request("status", new JSONObject(), token, 200).getString("state"));
        request("load", new JSONObject().put("url", "file:///private"), token, 400);
        assertEquals("idle", receiver().state);
    }
    @Test public void decodesSignedHlsWithRequiredSiteHeadersOnTheTv() throws Exception { play(false); }
    @Test public void restoresImageWrappedSegmentsOnTheTv() throws Exception { play(true); }
    @Test public void restoresTransportAppendedAfterWebpImageOnTheTv() throws Exception { play(2); }
    /** Explicit hardware diagnostic only; source context stays in an untracked device file. */
    @Test public void playsRequestedExternalSource() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("sourceFile");
        org.junit.Assume.assumeNotNull(path);
        JSONObject body;
        try (var input = new java.io.FileInputStream(path); var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) { assertTrue(out.size() + count <= 32768); out.write(buffer, 0, count); }
            body = new JSONObject(out.toString("UTF-8"));
        }
        String token = pair();
        try {
            request("load", body, token, 200);
            long deadline = SystemClock.uptimeMillis() + 45000;
            while (!receiver().state.equals("playing")) {
                assertFalse(receiver().error, receiver().state.equals("error"));
                assertTrue("TV did not start external source", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(200);
            }
            long refreshDeadline = SystemClock.uptimeMillis() + 8000;
            while (SystemClock.uptimeMillis() < refreshDeadline) {
                assertFalse(receiver().error, receiver().state.equals("error")); SystemClock.sleep(200);
            }
        } finally { request("stop", new JSONObject(), token, 200); }
    }
    private void play(boolean envelope) throws Exception { play(envelope ? 1 : 0); }
    private void play(int envelope) throws Exception {
        byte[] segment;
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("control.ts"); var out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            segment = out.toByteArray();
        }
        byte[] wire = segment;
        if (envelope == 1) {
            var out = new ByteArrayOutputStream(); out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            little(out, segment.length + 12 + (segment.length & 1)); out.write("WEBPEXIF".getBytes(StandardCharsets.US_ASCII));
            little(out, segment.length); out.write(segment); if ((segment.length & 1) != 0) out.write(0); wire = out.toByteArray();
        } else if (envelope == 2) {
            var out = new ByteArrayOutputStream(); out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            little(out, 16); out.write("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)); little(out, 4);
            out.write(new byte[4]); out.write(segment); wire = out.toByteArray();
        }
        byte[] payload = wire;
        AtomicInteger segmentRequests = new AtomicInteger(), signedManifestRequests = new AtomicInteger();
        NanoHTTPD fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (!"https://player.test/".equals(session.getHeaders().get("referer"))
                    || !"https://player.test".equals(session.getHeaders().get("origin")))
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Context required");
                if (session.getUri().equals("/index.m3u8")) {
                    if (!"temporary-stream-token".equals(session.getHeaders().get("x-token")))
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Token header missing");
                    if (!"t=A%2FB%3D".equals(session.getQueryParameterString())) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Token missing");
                    signedManifestRequests.incrementAndGet();
                    return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:6.0,\nsegment.ts\n#EXT-X-ENDLIST\n");
                }
                if (session.getHeaders().containsKey("x-token")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Manifest token leaked to a segment");
                segmentRequests.incrementAndGet();
                return newFixedLengthResponse(Response.Status.OK, envelope != 0 ? "image/webp" : "video/mp2t", new ByteArrayInputStream(payload), payload.length);
            }
        };
        fixture.start(5000, true);
        String token = pair();
        try {
            JSONObject headers = new JSONObject().put("Referer", "https://player.test/").put("Origin", "https://player.test").put("X-Token", "temporary-stream-token");
            request("load", new JSONObject().put("url", "http://127.0.0.1:" + fixture.getListeningPort() + "/index.m3u8?t=A%2FB%3D")
                .put("mime", "application/x-mpegURL").put("headers", headers), token, 200);
            long deadline = SystemClock.uptimeMillis() + 20000;
            while (!receiver().state.equals("playing")) {
                assertFalse(receiver().error, receiver().state.equals("error"));
                assertTrue("TV did not start playback", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
            }
            assertTrue(signedManifestRequests.get() > 0); assertTrue(segmentRequests.get() > 0);
        } finally {
            request("stop", new JSONObject(), token, 200); fixture.stop();
        }
    }
    private void little(ByteArrayOutputStream output, int value) { for (int i = 0; i < 4; i++) output.write(value >>> (i * 8) & 255); }
}
