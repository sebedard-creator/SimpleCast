package ca.sebed.simplecast;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Local WebView fixtures exercise cleaning in frames and the compatibility switch. */
@RunWith(AndroidJUnit4.class)
public class AdFilterTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class, false, false);
    private NanoHTTPD server;
    private WebView browser;
    private Boolean saved;
    @Before public void start() throws Exception {
        var prefs = InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("simplecast-browser", Context.MODE_PRIVATE);
        saved = prefs.contains("ad-filter") ? prefs.getBoolean("ad-filter", true) : null;
        prefs.edit().putBoolean("ad-filter", true).commit();
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                boolean frame = request.getUri().startsWith("/frame");
                boolean dialogs = request.getUri().contains("dialogs");
                String html = "<div class='advertisement' id='banner'>Ad</div>"
                    + "<div class='ad-container' id='video-wrap'><video aria-label='Main video'></video></div>";
                if (!frame) html += "<div class='ad-container' id='frame-wrap'><iframe id='player' src='" + (dialogs ? "/frame-dialogs" : "/frame") + "'></iframe></div>";
                if (dialogs) html += "<script>alert('Ad alert');window.confirmed=confirm('Ad confirm');"
                    + "window.prompted=prompt('Ad prompt');window.dialogsDone=true;</script>";
                return newFixedLengthResponse(Response.Status.OK, "text/html", html);
            }
        };
        server.start(5000, true); activity.launchActivity(null);
        browser = (WebView) field("browser");
    }
    @After public void stop() {
        if (server != null) server.stop();
        var edit = InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("simplecast-browser", Context.MODE_PRIVATE).edit();
        if (saved == null) edit.remove("ad-filter"); else edit.putBoolean("ad-filter", saved);
        edit.commit();
    }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity.getActivity());
    }
    private void load(String path) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> browser.loadUrl("http://127.0.0.1:" + server.getListeningPort() + path));
    }
    private Object evaluate(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> browser.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue(done.await(5, TimeUnit.SECONDS)); return new JSONTokener(result.get()).nextValue();
    }
    private void await(String condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!Boolean.TRUE.equals(evaluate(condition))) {
            assertTrue("Timed out: " + condition, SystemClock.uptimeMillis() < deadline); SystemClock.sleep(100);
        }
    }
    private void filter(boolean enabled) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                var method = MainActivity.class.getDeclaredMethod("setAdFilterEnabled", boolean.class); method.setAccessible(true);
                method.invoke(activity.getActivity(), enabled);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }
    @Test public void hidesAdsInFramesAndDynamicBannersWithoutHidingPlayersAndCanBeDisabled() throws Exception {
        assertTrue("Bundled hosts must be loaded", BrowserPolicy.blockedResource("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"));
        load("/cosmetic");
        await("document.getElementById('banner') && getComputedStyle(document.getElementById('banner')).display==='none'");
        await("document.getElementById('player').contentDocument.getElementById('banner') && document.getElementById('player').contentWindow.getComputedStyle(document.getElementById('player').contentDocument.getElementById('banner')).display==='none'");
        assertEquals(true, evaluate("getComputedStyle(document.getElementById('video-wrap')).display!=='none' && getComputedStyle(document.getElementById('frame-wrap')).display!=='none'"));
        evaluate("var dynamic=document.createElement('div');dynamic.id='dynamic';dynamic.className='ad-banner';document.body.appendChild(dynamic)");
        await("getComputedStyle(document.getElementById('dynamic')).display==='none'");
        evaluate("document.getElementById('dynamic').appendChild(document.createElement('video'))");
        await("getComputedStyle(document.getElementById('dynamic')).display!=='none'");
        filter(false);
        await("document.readyState==='complete' && document.getElementById('banner') && !window.__simpleCastCleanup && getComputedStyle(document.getElementById('banner')).display!=='none'");
        assertEquals(false, field("filterEnabled"));
        filter(true);
        await("document.getElementById('banner') && getComputedStyle(document.getElementById('banner')).display==='none'");
        assertEquals(true, field("filterEnabled"));
    }
    @Test public void suppressesPageAndIframeScriptDialogsWhileAllowingTheScriptsToContinue() throws Exception {
        load("/dialogs");
        await("window.dialogsDone===true && document.getElementById('player').contentWindow.dialogsDone===true");
        assertEquals(true, evaluate("window.confirmed===false && window.prompted===null"));
    }
}
