package ca.sebed.simplecast;

import android.app.ActivityManager;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RelayServiceTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    @Test public void runsAReachableForegroundRelayOnTheWifiInterface() throws Exception {
        AtomicReference<String> origin = new AtomicReference<>();
        NanoHTTPD source = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                origin.set(session.getHeaders().get("origin"));
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n");
            }
        };
        source.start(5000, true);
        AtomicReference<String> url = new AtomicReference<>(), failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try {
            RelayService.prepare(activity.getActivity(), "http://127.0.0.1:" + source.getListeningPort() + "/index.m3u8", "https://fixture.test/player", java.util.Collections.emptyMap(), true, new RelayService.Ready() {
                @Override public void ready(String value) { url.set(value); ready.countDown(); }
                @Override public void failed(String reason) { failure.set(reason); ready.countDown(); }
            });
            assertTrue("Relay startup completes", ready.await(20, TimeUnit.SECONDS));
            assertNull(failure.get(), failure.get()); assertNotNull(url.get());
            HttpURLConnection connection = (HttpURLConnection) new URL(url.get()).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            try (var input = connection.getInputStream(); var output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                assertTrue(output.toString("UTF-8").contains("/segment.ts"));
                assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"));
                assertEquals("https://fixture.test", origin.get());
            } finally { connection.disconnect(); }
            ActivityManager manager = (ActivityManager) activity.getActivity().getSystemService(Context.ACTIVITY_SERVICE);
            assertTrue("Relay has foreground service protection", manager.getRunningServices(50).stream()
                .anyMatch(service -> service.service.getClassName().equals(RelayService.class.getName()) && service.foreground));
        } finally { RelayService.stop(activity.getActivity()); source.stop(); }
    }
}
