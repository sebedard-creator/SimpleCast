package ca.sebed.simplecast;

import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises real DOM URL resolution and both document-start and legacy source scanning. */
@RunWith(AndroidJUnit4.class)
public class MediaSourceDetectionTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity.getActivity());
    }
    @Test public void excludesEmptyPlaceholdersAndKeepsCompleteSourcesFromPlayerMetadata() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        NanoHTTPD fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (session.getUri().equals("/index.m3u8")) return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL",
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n");
                if (session.getUri().equals("/media.json")) return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "[{\"videoUrl\":\"http://127.0.0.1:" + getListeningPort() + "/complete.mp4?token=A%2FB\",\"quality\":\"720\"}]");
                return newFixedLengthResponse(Response.Status.OK, "text/html",
                    "<video><source src='' type='video/mp4'></video><script>fetch('/index.m3u8');fetch('/media.json')</script>");
            }
        };
        fixture.start(5000, true);
        String page = "http://127.0.0.1:" + fixture.getListeningPort() + "/watch";
        try {
            instrumentation.runOnMainSync(() -> {
                try { ((WebView) field("browser")).loadUrl(page); } catch (Exception error) { throw new AssertionError(error); }
            });
            AtomicReference<List<StreamCatalog.Stream>> snapshot = new AtomicReference<>();
            long deadline = SystemClock.uptimeMillis() + 10000;
            do {
                instrumentation.runOnMainSync(() -> {
                    try { snapshot.set(((StreamCatalog) field("catalog")).all()); } catch (Exception error) { throw new AssertionError(error); }
                });
                if (snapshot.get().stream().anyMatch(s -> s.url.endsWith("/index.m3u8"))
                    && snapshot.get().stream().anyMatch(s -> s.url.endsWith("/complete.mp4?token=A%2FB"))) break;
                assertTrue("Expected complete source URLs from the real WebView", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
            } while (true);
            assertEquals(2, snapshot.get().size()); assertFalse(snapshot.get().stream().anyMatch(s -> s.url.equals(page)));
            instrumentation.runOnMainSync(() -> {
                try {
                    ((StreamCatalog) field("catalog")).clear();
                    var legacy = MainActivity.class.getDeclaredMethod("fallbackScan"); legacy.setAccessible(true); legacy.invoke(activity.getActivity());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            AtomicBoolean legacyDone = new AtomicBoolean(); deadline = SystemClock.uptimeMillis() + 5000;
            do {
                instrumentation.runOnMainSync(() -> {
                    try {
                        legacyDone.set(((Map<?, ?>) field("players")).containsKey(page + "\nvideo-0"));
                        snapshot.set(((StreamCatalog) field("catalog")).all());
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                if (legacyDone.get()) break;
                assertTrue("Legacy scan callback must complete", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
            } while (true);
            assertTrue("Legacy scan must also ignore the empty source", snapshot.get().isEmpty());
        } finally { fixture.stop(); }
    }
}
