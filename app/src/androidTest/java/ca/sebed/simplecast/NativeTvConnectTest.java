package ca.sebed.simplecast;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in setup for a real TV: -e tvEndpoint IP:PORT -e tvPin DISPLAYED_CODE. */
@RunWith(AndroidJUnit4.class)
public class NativeTvConnectTest {
    @Test public void pairExplicitTvAndRetainTheConnection() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var arguments = InstrumentationRegistry.getArguments();
        String endpoint = arguments.getString("tvEndpoint", ""), pin = arguments.getString("tvPin", "");
        assumeTrue("Explicit TV endpoint and code required", !endpoint.isEmpty() && pin.matches("[0-9]{6}"));
        NativeTvClient.Target target = NativeTvClient.manual(endpoint);
        NativeTvClient client = new NativeTvClient(instrumentation.getTargetContext());
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> client.pair(target, pin, new NativeTvClient.Result() {
            @Override public void complete(JSONObject response) { ready.countDown(); }
            @Override public void failed(String message) { error.set(message); ready.countDown(); }
        }));
        try {
            assertTrue("TV pairing timed out", ready.await(10, TimeUnit.SECONDS)); assertNull(error.get());
            assertTrue(client.paired());
            // Instrumentation can terminate before SharedPreferences.apply() reaches disk.
            assertTrue(instrumentation.getTargetContext().getSharedPreferences("simplecast-tv", android.content.Context.MODE_PRIVATE)
                .edit().commit());
        } finally { instrumentation.runOnMainSync(client::close); }
    }
}
