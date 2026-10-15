package ca.sebed.simplecast.tv;

import android.app.Activity;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import ca.sebed.simplecast.PlaybackContext;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@UnstableApi
public final class TvActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private PlayerView video;
    private TextView info;
    private ExoPlayer player;
    private ControlServer server;
    private NsdManager.RegistrationListener registration;
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean playing) {
            if (server == null || player == null) return;
            if (playing) { server.state = "playing"; server.error = ""; info.setVisibility(View.GONE); }
            else if (player.getPlaybackState() == Player.STATE_READY) server.state = "paused";
        }
        @Override public void onPlaybackStateChanged(int state) {
            if (server == null) return;
            if (state == Player.STATE_BUFFERING) server.state = "loading";
            if (state == Player.STATE_ENDED) { server.state = "ended"; showWelcome(); }
        }
        @Override public void onPlayerError(PlaybackException error) {
            if (server == null) return;
            String detail = error.getErrorCodeName();
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                    detail += " · HTTP " + ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            server.error = detail; server.state = "error";
            info.setText("Lecture impossible\n" + detail + "\nChoisis un autre flux depuis SimpleCast.");
            info.setVisibility(View.VISIBLE);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.rgb(16, 34, 37));
        video = new PlayerView(this); video.setKeepScreenOn(true); root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        info = new TextView(this); info.setTextColor(Color.rgb(235, 245, 241)); info.setTextSize(24);
        info.setGravity(Gravity.CENTER); info.setPadding(48, 32, 48, 32); info.setBackgroundColor(0xE6102225);
        root.addView(info, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        video.requestFocus();
    }
    @Override public void onStart() {
        super.onStart();
        player = new ExoPlayer.Builder(this).build(); player.addListener(listener); video.setPlayer(player);
        server = new ControlServer((command, body) -> main.post(() -> apply(command, body)));
        try { server.start(5000, true); advertise(); showWelcome(); }
        catch (Exception error) { info.setText("Connexion locale indisponible. Rouvre SimpleCast TV."); }
    }
    private void apply(String command, JSONObject body) {
        if (player == null || server == null) return;
        if (command.equals("load")) {
            Map<String, String> observed = new LinkedHashMap<>();
            JSONObject supplied = body.optJSONObject("headers");
            if (supplied != null) supplied.keys().forEachRemaining(key -> observed.put(key, supplied.optString(key)));
            Map<String, String> headers = PlaybackContext.headers("", observed, "SimpleCastTV/0.1");
            Map<String, String> ordinaryHeaders = new LinkedHashMap<>(headers); ordinaryHeaders.remove("X-Token");
            String mediaUrl = body.optString("url");
            var http = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(ordinaryHeaders)
                .setUserAgent(headers.get("User-Agent")).setConnectTimeoutMs(12000).setReadTimeoutMs(12000);
            player.setMediaSource(new DefaultMediaSourceFactory(() -> new EnvelopeDataSource(
                new ManifestTokenDataSource(http.createDataSource(), mediaUrl, headers)))
                .createMediaSource(new MediaItem.Builder().setUri(mediaUrl).setMimeType(body.optString("mime", null)).build()));
            info.setText("Chargement de la vidéo…"); info.setVisibility(View.VISIBLE);
            server.error = ""; server.state = "loading";
            player.prepare(); player.play();
        } else if (command.equals("play")) player.play();
        else if (command.equals("pause")) player.pause();
        else if (command.equals("stop")) { player.stop(); player.clearMediaItems(); server.state = "idle"; showWelcome(); }
    }
    private void showWelcome() {
        if (server == null) return;
        info.setText("SimpleCast TV\n\nSur ton téléphone, ouvre SimpleCast puis appuie sur « TV ».\n"
            + "Code de connexion : " + server.pin() + "\nAdresse : " + address() + ":" + server.getListeningPort()
            + "\n\nLa vidéo est téléchargée directement par la télé.");
        info.setVisibility(View.VISIBLE);
    }
    private String address() {
        try {
            for (var network : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (var ip : Collections.list(network.getInetAddresses()))
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip.getHostAddress();
        } catch (Exception ignored) { }
        return "Adresse indisponible";
    }
    private void advertise() {
        NsdServiceInfo service = new NsdServiceInfo(); service.setServiceName("SimpleCast TV · " + android.os.Build.MODEL);
        service.setServiceType(PlaybackContext.SERVICE_TYPE); service.setPort(server.getListeningPort());
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo value) { }
            @Override public void onRegistrationFailed(NsdServiceInfo value, int error) { }
            @Override public void onServiceUnregistered(NsdServiceInfo value) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo value, int error) { }
        };
        ((NsdManager) getSystemService(NSD_SERVICE)).registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
    }
    @Override public void onStop() {
        main.removeCallbacksAndMessages(null);
        if (registration != null) {
            try { ((NsdManager) getSystemService(NSD_SERVICE)).unregisterService(registration); }
            catch (RuntimeException ignored) { }
            registration = null;
        }
        if (server != null) { server.stop(); server = null; }
        video.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        super.onStop();
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        return video.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
}
