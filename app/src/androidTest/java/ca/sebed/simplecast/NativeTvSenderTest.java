package ca.sebed.simplecast;

import android.content.Context;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeTvSenderTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class, false, false);
    private final AtomicReference<JSONObject> loaded = new AtomicReference<>();
    private NanoHTTPD fixture;
    private Map<String, ?> saved;
    @Before public void setup() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var preferences = context.getSharedPreferences("simplecast-tv", Context.MODE_PRIVATE);
        saved = new HashMap<>(preferences.getAll()); preferences.edit().clear().commit();
        fixture = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                try {
                    if (session.getMethod() == Method.GET) {
                        if (session.getUri().equals("/watch")) return newFixedLengthResponse(Response.Status.OK, "text/html",
                            "<script>fetch('/live.m3u8',{headers:{'x-token':'temporary-stream-token'}})</script>");
                        return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", "#EXTM3U\n");
                    }
                    Map<String, String> files = new HashMap<>(); session.parseBody(files);
                    JSONObject body = new JSONObject(files.get("postData"));
                    if (session.getUri().equals("/pair")) return ok("{\"token\":\"fixture-token\"}");
                    if (!"fixture-token".equals(session.getHeaders().get("x-simplecast-token")))
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{}");
                    if (session.getUri().equals("/load")) loaded.set(body);
                    return ok(session.getUri().equals("/status") ? "{\"state\":\"playing\"}" : "{\"accepted\":true}");
                } catch (Exception error) { return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{}"); }
            }
            private Response ok(String body) { return newFixedLengthResponse(Response.Status.OK, "application/json", body); }
        };
        fixture.start(5000, true); activity.launchActivity(null);
    }
    @After public void cleanup() {
        if (fixture != null) fixture.stop();
        var edit = InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("simplecast-tv", Context.MODE_PRIVATE).edit().clear();
        saved.forEach((key, value) -> { if (value instanceof String) edit.putString(key, (String) value); else if (value instanceof Integer) edit.putInt(key, (Integer) value); });
        edit.commit();
    }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity.getActivity());
    }
    @Test public void capturesCustomManifestTokenFromRealWebViewRequest() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        String base = "http://127.0.0.1:" + fixture.getListeningPort();
        instrumentation.runOnMainSync(() -> {
            try { ((android.webkit.WebView) field("browser")).loadUrl(base + "/watch"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                try {
                    @SuppressWarnings("unchecked") Map<String, Map<String, String>> contexts = (Map<String, Map<String, String>>) field("requestContexts");
                    observed.set(contexts.get(base + "/live.m3u8"));
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            if (observed.get() != null) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertNotNull("Manifest request context must be captured", observed.get());
        Map<String, String> headers = PlaybackContext.headers("", observed.get(), "Browser/1");
        assertEquals("temporary-stream-token", headers.get("X-Token"));
        assertEquals(base + "/watch", headers.get("Referer"));
    }
    @Test public void sendsCompleteUrlAndSafeContextThenShowsConfirmedTvPlayback() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        NativeTvClient client = (NativeTvClient) field("nativeTv");
        CountDownLatch paired = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> client.pair(new NativeTvClient.Target("127.0.0.1", fixture.getListeningPort(), "TV fixture"), "123456", new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) { paired.countDown(); }
            @Override public void failed(String message) { error.set(message); paired.countDown(); }
        }));
        assertTrue(paired.await(5, TimeUnit.SECONDS)); assertNull(error.get());
        String url = "https://cdn.test/live.m3u8?t=A%2FB%3D&expires=123";
        instrumentation.runOnMainSync(() -> {
            try {
                @SuppressWarnings("unchecked") Map<String, Map<String, String>> contexts = (Map<String, Map<String, String>>) field("requestContexts");
                contexts.put(url, Map.of("Referer", "https://player.test/embed", "Origin", "https://player.test", "x-token", "temporary-stream-token", "Cookie", "private", "Authorization", "private"));
                var method = MainActivity.class.getDeclaredMethod("sendNativeTv", StreamCatalog.Stream.class); method.setAccessible(true);
                method.invoke(activity.getActivity(), new StreamCatalog.Stream(url, "application/x-mpegURL", "https://player.test/embed", "video-1", "Test", true));
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        AtomicReference<String> status = new AtomicReference<>(); long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                try { status.set(((TextView) field("status")).getText().toString()); } catch (Exception failure) { throw new AssertionError(failure); }
            });
            if (status.get().equals("Lecture directe sur SimpleCast TV")) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("Lecture directe sur SimpleCast TV", status.get());
        assertNotNull(loaded.get()); assertEquals(url, loaded.get().getString("url"));
        JSONObject headers = loaded.get().getJSONObject("headers");
        assertEquals("https://player.test/embed", headers.getString("Referer"));
        assertEquals("https://player.test", headers.getString("Origin"));
        assertEquals("temporary-stream-token", headers.getString("X-Token"));
        assertFalse(headers.has("Cookie")); assertFalse(headers.has("Authorization"));
    }
}
