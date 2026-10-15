package ca.sebed.simplecast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import androidx.core.content.ContextCompat;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.io.IOException;
import java.net.Inet4Address;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the selected phone relay alive when the activity or screen closes. */
public final class RelayService extends Service {
    public interface Ready { void ready(String url); void failed(String reason); }
    private static final String STOP = "ca.sebed.simplecast.STOP_RELAY";
    private static final String CHANNEL = "video-relay";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger TICKET = new AtomicInteger();
    private static volatile LocalRelay active;
    private static volatile String recentError = "";
    private LocalRelay owned;
    private PowerManager.WakeLock wakeLock;
    private CastContext castContext;
    private RemoteMediaClient remote;
    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() {
            if (remote == null || owned == null) return;
            MediaInfo info = remote.getMediaInfo(); MediaStatus state = remote.getMediaStatus();
            if (info != null && owned.rootUrl().equals(info.getContentId()) && state != null
                && state.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE && state.getIdleReason() != MediaStatus.IDLE_REASON_NONE)
                stop(RelayService.this, owned.rootUrl());
        }
    };
    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<>() {
        @Override public void onSessionEnded(CastSession session, int error) { stop(RelayService.this); }
        @Override public void onSessionStarted(CastSession session, String id) { watch(session); }
        @Override public void onSessionResumed(CastSession session, boolean suspended) { watch(session); }
        @Override public void onSessionStarting(CastSession session) { }
        @Override public void onSessionStartFailed(CastSession session, int error) { }
        @Override public void onSessionEnding(CastSession session) { }
        @Override public void onSessionResuming(CastSession session, String id) { }
        @Override public void onSessionResumeFailed(CastSession session, int error) { }
        @Override public void onSessionSuspended(CastSession session, int reason) { }
    };
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Runnable renew = new Runnable() {
        @Override public void run() {
            if (active == null) return;
            wakeLock.acquire(70 * 60 * 1000L);
            timer.postDelayed(this, 60 * 60 * 1000L);
        }
    };

    public static synchronized void prepare(Context context, String source, String frame, Map<String, String> headers, boolean hls, Ready ready) {
        Context app = context.getApplicationContext(); int ticket = TICKET.incrementAndGet();
        IO.execute(() -> {
            LocalRelay relay = null;
            try {
                LocalRelay previous;
                synchronized (RelayService.class) {
                    if (ticket != TICKET.get()) return;
                    previous = active; active = null;
                }
                if (previous != null) previous.closeMedia();
                recentError = "";
                relay = new LocalRelay(wifiAddress(app), source, frame, headers, url -> CookieManager.getInstance().getCookie(url));
                relay.startMedia(hls);
                synchronized (RelayService.class) {
                    if (ticket == TICKET.get()) {
                        active = relay;
                        ContextCompat.startForegroundService(app, new Intent(app, RelayService.class));
                    }
                }
                if (ticket != TICKET.get()) { relay.closeMedia(); return; }
                String url = relay.rootUrl();
                MAIN.post(() -> { if (ticket == TICKET.get()) ready.ready(url); });
            } catch (IOException | RuntimeException error) {
                synchronized (RelayService.class) {
                    if (active == relay) active = null;
                    if (ticket == TICKET.get()) {
                        app.stopService(new Intent(app, RelayService.class));
                        String message = error instanceof IOException ? error.getMessage() : "Impossible de démarrer le relais vidéo.";
                        MAIN.post(() -> { if (ticket == TICKET.get()) ready.failed(message); });
                    }
                }
                if (relay != null) relay.closeMedia();
            }
        });
    }
    public static synchronized void stop(Context context) { stop(context, null); }
    public static synchronized void stop(Context context, String expectedUrl) {
        LocalRelay relay = active;
        if (expectedUrl != null && (relay == null || !expectedUrl.equals(relay.rootUrl()))) return;
        TICKET.incrementAndGet(); active = null;
        if (relay != null) { recentError = relay.lastError(); IO.execute(relay::closeMedia); }
        context.getApplicationContext().stopService(new Intent(context, RelayService.class));
    }
    public static String lastError() { LocalRelay relay = active; return relay == null ? recentError : relay.lastError(); }
    public static boolean isRelayUrl(String url) { LocalRelay relay = active; return relay != null && relay.rootUrl().equals(url); }

    private static String wifiAddress(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (capabilities == null || properties == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address && !address.getAddress().isLoopbackAddress()
                    && !address.getAddress().isLinkLocalAddress()) return address.getAddress().getHostAddress();
            }
        }
        throw new IOException("Connecte ton téléphone au même réseau Wi-Fi que le Chromecast pour utiliser le relais.");
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Diffusion vers la télé", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RelayService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SimpleCast").setContentText("Relais vidéo vers la télé · garde le Wi-Fi connecté")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Arrêter le relais", stop).build()).build();
        startForeground(31, notification);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleCast:Relay");
        wakeLock.setReferenceCounted(false);
        try {
            castContext = CastContext.getSharedInstance(this);
            castContext.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
            watch(castContext.getSessionManager().getCurrentCastSession());
        } catch (RuntimeException ignored) { }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || active == null) {
            stop(this); stopSelf(); return START_NOT_STICKY;
        }
        owned = active; timer.removeCallbacks(renew); renew.run();
        return START_NOT_STICKY; // No media session survives a process restart.
    }
    @Override public void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (remote != null) remote.unregisterCallback(mediaCallback);
        if (castContext != null) castContext.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        synchronized (RelayService.class) {
            if (owned != null && owned == active) {
                LocalRelay relay = active; recentError = relay.lastError(); active = null; TICKET.incrementAndGet(); IO.execute(relay::closeMedia);
            }
        }
        super.onDestroy();
    }
    private void watch(CastSession session) {
        if (remote != null) remote.unregisterCallback(mediaCallback);
        remote = session == null ? null : session.getRemoteMediaClient();
        if (remote != null) remote.registerCallback(mediaCallback);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
