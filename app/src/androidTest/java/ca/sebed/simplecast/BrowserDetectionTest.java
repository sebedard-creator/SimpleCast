package ca.sebed.simplecast;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Tests only our app and our bundled demo; does not require a Chromecast or remote video playback. */
@RunWith(AndroidJUnit4.class)
public class BrowserDetectionTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
        return field.get(activity.getActivity());
    }
    private Object invoke(String name) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true);
            return method.invoke(activity.getActivity());
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    @Test public void listsThreePlayersIncludingIframeAndShowsTheirNames() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            try { ((EditText) field("address")).setText("https://simplecast.test/demo"); }
            catch (Exception exception) { throw new AssertionError(exception); }
            invoke("openInput");
        });
        AtomicInteger count = new AtomicInteger();
        long deadline = SystemClock.uptimeMillis() + 30000;
        do {
            instrumentation.runOnMainSync(() -> {
                try { count.set(((Map<?, ?>) field("players")).size()); }
                catch (Exception exception) { throw new AssertionError(exception); }
            });
            if (count.get() == 3) break;
            SystemClock.sleep(200);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("Document-start detection must reach the iframe", 3, count.get());
        AtomicReference<AlertDialog> chooser = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                StreamCatalog catalog = (StreamCatalog) field("catalog");
                assertEquals(3, catalog.size());
                assertTrue(catalog.all().stream().anyMatch(s -> s.url.endsWith("Sintel.mp4")
                    && s.frame.equals("https://simplecast.test/embedded-demo")
                    && s.exactMatch(s.frame, "video-1")));
            } catch (Exception exception) { throw new AssertionError(exception); }
            chooser.set((AlertDialog) invoke("showPlayers"));
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertNotNull(chooser.get());
            assertTrue(chooser.get().isShowing());
            View root = chooser.get().getWindow().getDecorView();
            String labels = textIn(root);
            assertTrue(labels, labels.contains("Big Buck Bunny"));
            assertTrue(labels, labels.contains("Elephants Dream"));
            assertTrue(labels, labels.contains("Sintel"));
            assertTrue("Chooser has a laid out viewport", root.getWidth() > 0 && root.getHeight() > 0);
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(bitmap));
            File screenshot = new File(instrumentation.getTargetContext().getCacheDir(), "qa-players.png");
            try (FileOutputStream output = new FileOutputStream(screenshot)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } catch (Exception exception) { throw new AssertionError(exception); }
            finally { bitmap.recycle(); }
        });
    }
    private String textIn(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                text.append(textIn(group.getChildAt(i)));
            }
        }
        return text.toString();
    }
}
