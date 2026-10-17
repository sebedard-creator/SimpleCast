package ca.sebed.simplecast.tv;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReceiverPairingTest {
    private JSONObject post(ControlServer server, String action, JSONObject body, String token, int expected) throws Exception {
        var connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getListeningPort() + "/" + action).openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if (token != null) connection.setRequestProperty("X-SimpleCast-Token", token);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (var output = connection.getOutputStream()) { output.write(bytes); }
            assertEquals(expected, connection.getResponseCode());
            try (var input = expected == 200 ? connection.getInputStream() : connection.getErrorStream()) {
                return new JSONObject(new String(ca.sebed.simplecast.SegmentDecoder.readLimited(input, 32768), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    @Test public void restoresPairedCredentialAndPortAfterReceiverRestart() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var saved = context.getSharedPreferences("receiver-pairing-test", Context.MODE_PRIVATE);
        saved.edit().clear().commit();
        ControlServer server = null;
        try {
            server = ControlServer.open((command, body) -> { }, saved);
            String token = post(server, "pair", new JSONObject().put("pin", server.pin()), null, 200).getString("token");
            int port = server.getListeningPort(); server.stop();
            server = ControlServer.open((command, body) -> { }, saved);
            assertEquals(port, server.getListeningPort());
            assertEquals("idle", post(server, "status", new JSONObject(), token, 200).getString("state"));
            post(server, "status", new JSONObject(), "wrong-token", 403);
            post(server, "load", new JSONObject().put("url", "file:///bad"), token, 400);
            post(server, "status", new JSONObject(), token, 200);
            String replacement = post(server, "pair", new JSONObject().put("pin", server.pin()), null, 200).getString("token");
            assertNotEquals(token, replacement);
            post(server, "status", new JSONObject(), token, 403);
            post(server, "status", new JSONObject(), replacement, 200);
        } finally { if (server != null) server.stop(); saved.edit().clear().commit(); }
    }
}
