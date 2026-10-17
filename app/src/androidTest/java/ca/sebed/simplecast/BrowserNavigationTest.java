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
                if (path.equals("/probe-host")) html = "<meta name='viewport' content='width=device-width,initial-scale=1'><iframe id='probe-frame' src='/probe-frame' width='320' height='180'></iframe>";
                else if (path.equals("/probe-frame")) html = "<button id='probe' style='width:200px;height:60px' onclick=\""
                    + "var p=window.open('about:blank','_blank');window.probeOpened=!!p;if(p)p.close();window.clicked=true;\">Play</button>"
                    + "<button id='escape' style='width:200px;height:60px' onclick=\"window.open('" + other + "/popup','_blank');window.escaped=true;\">Popup</button>"
                    + "<script>var p=window.open('about:blank','_blank');window.automaticProbeOpened=!!p;if(p)p.close();</script>";
                else if (path.equals("/pdf-host")) html = "<iframe id='pdf-frame' src='/pdf-probe'></iframe>";
                else if (path.equals("/pdf-probe")) html = "<script>window.sandboxWarningCount=0;window.sandDetect=()=>window.sandboxWarningCount++;</script>"
                    + "<object data='data:application/pdf;base64,aG1t' onerror='window.sandDetect()' width='1' height='1'></object>"
                    + "<object data='data:application/pdf;base64,YmFk' onerror='window.normalPdfError=true' width='1' height='1'></object>"
                    + "<video aria-label='Working player' src='/video.mp4'></video>";
                else if (path.equals("/player-two")) html = "<video aria-label='Replacement player' src='/replacement.mp4'></video>";
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

    @Test public void zoomsInAndOutWithRealTwoFingerGestures() throws Exception {
        load(page); await("document.readyState==='complete' && !!document.getElementById('normal')");
        double initial = ((Number) evaluate("visualViewport.scale")).doubleValue();
        pinch(0.09f, 0.30f);
        await("visualViewport.scale>" + (initial * 1.5));
        double enlarged = ((Number) evaluate("visualViewport.scale")).doubleValue();
        pinch(0.30f, 0.09f);
        await("visualViewport.scale<" + (enlarged * 0.8));
        assertEquals("Zooming must preserve the page", page, currentUrl());
    }
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
    @Test public void ignoresOnlyTheKnownPdfProbeFailureInsideAnIframeAndKeepsTheVideo() throws Exception {
        load("http://127.0.0.1:" + server.getListeningPort() + "/pdf-host");
        await("!!document.getElementById('pdf-frame') && document.getElementById('pdf-frame').contentWindow.normalPdfError===true");
        assertEquals("Ordinary PDF errors must still be delivered", true, evaluate("document.getElementById('pdf-frame').contentWindow.normalPdfError===true"));
        assertEquals("The invalid sandbox probe must not replace the player", true, evaluate("document.getElementById('pdf-frame').contentWindow.sandboxWarningCount===0"));
        awaitPlayers();
        assertEquals(true, evaluate("!!document.getElementById('pdf-frame').contentDocument.querySelector('video')"));
    }
    @Test public void containsAutomaticAndClickedPopupsWithoutFetchingTheirDestinations() throws Exception {
        load(page); await("document.readyState==='complete' && !!document.getElementById('popup')");
        evaluate("localStorage.setItem('sndbxchckiborjforqp2',JSON.stringify({isSandboxed:true,expiresAt:Date.now()+3600000}));localStorage.setItem('unrelated-fixture','kept')");
        String host = "http://127.0.0.1:" + server.getListeningPort() + "/probe-host";
        load(host); await("!!(document.getElementById('probe-frame') && document.getElementById('probe-frame').contentDocument.getElementById('probe'))");
        assertEquals(true, evaluate("localStorage.getItem('sndbxchckiborjforqp2')===null && localStorage.getItem('unrelated-fixture')==='kept'"));
        await("document.getElementById('probe-frame').contentWindow.automaticProbeOpened===true");
        tapFrame("probe"); await("document.getElementById('probe-frame').contentWindow.clicked===true");
        assertEquals(true, evaluate("document.getElementById('probe-frame').contentWindow.probeOpened===true"));
        tapFrame("escape"); await("document.getElementById('probe-frame').contentWindow.escaped===true");
        assertEquals(host, currentUrl());
        long deadline = SystemClock.uptimeMillis() + 5000; AtomicInteger count = new AtomicInteger();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    Field field = MainActivity.class.getDeclaredField("blockedPopups"); field.setAccessible(true);
                    count.set(((java.util.Set<?>) field.get(activity.getActivity())).size());
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (count.get() == 0) break;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("Contained windows must be released", 0, count.get());
        assertEquals("No popup request may reach the local fixture server", 0, unwantedLoads.get());
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
        tapPoint(bounds);
    }
    private void tapFrame(String id) throws Exception {
        JSONArray bounds = (JSONArray) evaluate("(()=>{let f=document.getElementById('probe-frame'),r=f.getBoundingClientRect(),b=f.contentDocument.getElementById('" + id + "').getBoundingClientRect();return [r.x+f.clientLeft+b.x+b.width/2,r.y+f.clientTop+b.y+b.height/2]})()");
        tapPoint(bounds);
    }
    private void pinch(float from, float to) {
        float[] frame = new float[4];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int[] location = new int[2]; browser.getLocationOnScreen(location);
            frame[0] = location[0] + browser.getWidth() / 2f;
            frame[1] = location[1] + browser.getHeight() / 2f;
            frame[2] = browser.getWidth();
        });
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] points = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            points[i] = new MotionEvent.PointerCoords();
            points[i].y = frame[1]; points[i].pressure = 1; points[i].size = 1;
            points[i].x = frame[0] + (i == 0 ? -1 : 1) * frame[2] * from;
        }
        long start = SystemClock.uptimeMillis();
        sendTouch(start, MotionEvent.ACTION_DOWN, 1, properties, points);
        sendTouch(start, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, points);
        for (int step = 1; step <= 20; step++) {
            SystemClock.sleep(20);
            float gap = frame[2] * (from + (to - from) * step / 20f);
            points[0].x = frame[0] - gap; points[1].x = frame[0] + gap;
            sendTouch(start, MotionEvent.ACTION_MOVE, 2, properties, points);
        }
        sendTouch(start, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, points);
        sendTouch(start, MotionEvent.ACTION_UP, 1, properties, points);
    }
    private void sendTouch(long start, int action, int count, MotionEvent.PointerProperties[] properties, MotionEvent.PointerCoords[] points) {
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, points,
            0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event); }
        finally { event.recycle(); }
    }
    private void tapPoint(JSONArray bounds) {
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
