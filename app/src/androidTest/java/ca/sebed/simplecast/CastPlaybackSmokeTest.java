package ca.sebed.simplecast;

import android.os.SystemClock;
import android.util.Log;
import androidx.mediarouter.media.MediaRouter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in hardware check: -e castDevice EXACT_NAME. Never selects a TV in the normal test suite. */
@RunWith(AndroidJUnit4.class)
public class CastPlaybackSmokeTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class, false, false);

    private void set(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
        field.set(activity.getActivity(), value);
    }

    @Test public void directHlsPlaysThroughAndroidSender() throws Exception {
        String device = InstrumentationRegistry.getArguments().getString("castDevice", "");
        assumeTrue("Explicit device required for hardware playback", !device.isEmpty());
        String url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
        activity.launchActivity(null);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        MediaRouter.Callback discovery = new MediaRouter.Callback() {};
        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean selected = new AtomicBoolean();
        AtomicBoolean playing = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> {
                var context = CastContext.getSharedInstance(activity.getActivity());
                MediaRouter.getInstance(activity.getActivity()).addCallback(context.getMergedSelector(),
                    discovery, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
            });
            long deadline = SystemClock.uptimeMillis() + 30000;
            do {
                instrumentation.runOnMainSync(() -> {
                    var router = MediaRouter.getInstance(activity.getActivity());
                    if (!selected.get()) {
                        for (var route : router.getRoutes()) {
                            if (device.equals(route.getName()) && route.isEnabled()
                                && route.matchesSelector(CastContext.getSharedInstance(activity.getActivity()).getMergedSelector())) {
                                selected.set(true);
                                if (!route.isSelected()) route.select();
                                return;
                            }
                        }
                        return;
                    }
                    CastSession session = CastContext.getSharedInstance(activity.getActivity())
                        .getSessionManager().getCurrentCastSession();
                    connected.set(session != null && session.isConnected() && session.getRemoteMediaClient() != null
                        && device.equals(session.getCastDevice().getFriendlyName()));
                    if (connected.get()) {
                        try {
                            set("pending", new StreamCatalog.Stream(url, "application/x-mpegURL", "", "", "SimpleCast · test HLS", true));
                            set("pendingLive", true); set("pendingRelay", false);
                            Method method = MainActivity.class.getDeclaredMethod("connected", CastSession.class);
                            method.setAccessible(true); method.invoke(activity.getActivity(), session);
                        } catch (Exception error) { throw new AssertionError(error); }
                    }
                });
                if (connected.get()) break;
                SystemClock.sleep(300);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue("Could not connect to " + device, connected.get());
            deadline = SystemClock.uptimeMillis() + 55000;
            do {
                instrumentation.runOnMainSync(() -> {
                    var session = CastContext.getSharedInstance(activity.getActivity()).getSessionManager().getCurrentCastSession();
                    if (session == null || session.getRemoteMediaClient() == null) return;
                    var remote = session.getRemoteMediaClient();
                    playing.set(remote.isPlaying() && remote.getMediaInfo() != null && url.equals(remote.getMediaInfo().getContentId()));
                });
                if (playing.get()) break;
                SystemClock.sleep(500);
            } while (SystemClock.uptimeMillis() < deadline);
            assertTrue("Receiver never played the exact HLS URL", playing.get());
            Log.i("SimpleCast", "Hardware check: direct HLS PLAYING on " + device);
        } finally {
            instrumentation.runOnMainSync(() -> {
                MediaRouter.getInstance(activity.getActivity()).removeCallback(discovery);
                var session = CastContext.getSharedInstance(activity.getActivity()).getSessionManager().getCurrentCastSession();
                if (session != null && session.getRemoteMediaClient() != null) {
                    var remote = session.getRemoteMediaClient();
                    if (remote.getMediaInfo() != null && url.equals(remote.getMediaInfo().getContentId())
                        && remote.getMediaInfo().getMetadata() != null
                        && "SimpleCast · test HLS".equals(remote.getMediaInfo().getMetadata().getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))) remote.stop();
                }
            });
        }
    }
}
