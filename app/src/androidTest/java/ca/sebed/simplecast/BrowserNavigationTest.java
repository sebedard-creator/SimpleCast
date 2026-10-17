package ca.sebed.simplecast;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real WebView navigation, including popunders caused by actual touch gestures. */
@RunWith(AndroidJUnit4.class)
public class BrowserNavigationTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private NanoHTTPD server;
    private WebView browser;
    private String page, other;
    private final AtomicInteger unwantedLoads = new AtomicInteger();

    @Before public void start() throws Exception {
        Field field = MainActivity.class.getDeclaredField("browser"); field.setAccessible(true);
        browser = (WebView) field.get(activity.getActivity());
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                String path = request.getUri();
                if (path.equals("/popup") || path.equals("/takeover")) unwantedLoads.incrementAndGet();
                if (path.equals("/redirect")) {
                    Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", other + "/landing"); return response;
                }
                String html;
                if (path.equals("/player-two")) html = "<video aria-label='Replacement player' src='/replacement.mp4'></video>";
                else if (path.equals("/player")) html = "<video aria-label='Embedded player' src='/video.mp4'></video>"
                    + "<script>onmessage=()=>{top.location='" + other + "/takeover'};</script>";
                else if (path.equals("/index")) html = "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<button id='popup' onclick=\"window.clicked=(window.clicked||0)+1;window.open('" + other + "/popup','_blank')\" style='width:200px;height:60px'>Play</button><br>"
                    + "<a id='blank' target='_blank' onclick='window.clicked++' href='" + other + "/popup' style='display:block;width:200px;height:60px'>New window</a>"
                    + "<a id='normal' href='" + other + "/landing' style='display:block;width:200px;height:60px'>Normal link</a>"
                    + "<iframe id='player' src='" + other + "/player'></iframe>";
                else html = "<p id='landed'>Landing page</p>";
                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            }
        };
        server.start(5000, true);
        page = "http://127.0.0.1:" + server.getListeningPort() + "/index";
        other = "http://localhost:" + server.getListeningPort();
    }
    @After public void stop() { if (server != null) server.stop(); }

    @Test public void rejectsTouchPopupsAndIframeTakeoversWhileKeepingThePlayer() throws Exception {
        load(page); await("document.readyState==='complete' && !!document.getElementById('popup')");
        awaitPlayers();
        tap("popup"); await("window.clicked===1");
        tap("blank"); await("window.clicked===2");
        evaluate("document.getElementById('player').contentWindow.postMessage('try','*')");
        SystemClock.sleep(700); // Allow an unwanted navigation to arrive, if protection regresses.
        assertEquals("Popups and iframe top navigation must not be fetched", 0, unwantedLoads.get());
        assertEquals(page, currentUrl());
        assertTrue("The embedded video still appears in detection", playerCount() > 0);
    }
    @Test public void permitsNormalCrossSiteClicksAndServerRedirects() throws Exception {
        load(page); await("document.readyState==='complete' && !!document.getElementById('normal')");
        tap("normal"); awaitUrl(other + "/landing"); await("!!document.getElementById('landed')");
        load("http://127.0.0.1:" + server.getListeningPort() + "/redirect");
        awaitUrl(other + "/landing"); await("!!document.getElementById('landed')");
    }
    @Test public void rescanDropsThePlayerFromAReplacedIframe() throws Exception {
        load(page); await("document.readyState==='complete' && !!document.getElementById('player')");
        awaitPlayers();
        evaluate("document.getElementById('player').src='" + other + "/player-two'");
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!playerKeys().contains(other + "/player-two\nvideo-1")) {
            assertTrue("Replacement iframe is detected", SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(100);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                var method = MainActivity.class.getDeclaredMethod("chooseSource"); method.setAccessible(true);
                method.invoke(activity.getActivity());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        deadline = SystemClock.uptimeMillis() + 10000;
        while (!playerKeys().equals(java.util.Set.of(other + "/player-two\nvideo-1"))) {
            assertTrue("Only the current iframe must answer the fresh scan", SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(100);
        }
    }
    private java.util.Set<?> playerKeys() throws Exception {
        Field field = MainActivity.class.getDeclaredField("players"); field.setAccessible(true);
        AtomicReference<java.util.Set<?>> keys = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { keys.set(new java.util.HashSet<>(((java.util.Map<?, ?>) field.get(activity.getActivity())).keySet())); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        return keys.get();
    }

    private void load(String url) { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> browser.loadUrl(url)); }
    private Object evaluate(String script) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> browser.evaluateJavascript(script, value -> { result.set(value); ready.countDown(); }));
        assertTrue("JavaScript evaluation completes", ready.await(5, TimeUnit.SECONDS));
        return new JSONTokener(result.get()).nextValue();
    }
    private void await(String condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!Boolean.TRUE.equals(evaluate(condition))) {
            assertTrue("Timed out: " + condition, SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
        }
    }
    private String currentUrl() {
        AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(browser.getUrl())); return value.get();
    }
    private void awaitUrl(String expected) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!expected.equals(currentUrl())) {
            assertTrue("Timed out navigating to " + expected, SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
        }
    }
    private int playerCount() throws Exception {
        Field field = MainActivity.class.getDeclaredField("players"); field.setAccessible(true); AtomicInteger count = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { count.set(((java.util.Map<?, ?>) field.get(activity.getActivity())).size()); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        }); return count.get();
    }
    private void awaitPlayers() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (playerCount() == 0) { assertTrue("Embedded player detection", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100); }
    }
    private void tap(String id) throws Exception {
        JSONArray bounds = (JSONArray) evaluate("(()=>{let r=document.getElementById('" + id + "').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()");
        float[] point = new float[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] location = new int[2]; browser.getLocationOnScreen(location);
            point[0] = location[0] + (float) bounds.optDouble(0) * browser.getScale();
            point[1] = location[1] + (float) bounds.optDouble(1) * browser.getScale();
        });
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        MotionEvent up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, point[0], point[1], 0);
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(down); InstrumentationRegistry.getInstrumentation().sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
    }
}
