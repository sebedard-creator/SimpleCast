package ca.sebed.simplecast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Exchanges small control messages; never downloads or relays the selected video. */
final class NativeTvClient {
    static final class Target {
        final String host, name;
        final int port;
        Target(String host, int port, String name) { this.host = host; this.port = port; this.name = name; }
        String base() { return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    }
    interface Result { void complete(JSONObject response); void failed(String message); }
    private final NsdManager nsd;
    private final android.content.SharedPreferences saved;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Target> found = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> queue = new ArrayDeque<>();
    private NsdManager.DiscoveryListener discovery;
    private Consumer<List<Target>> changed;
    private boolean resolving, closed;
    private int discoveryGeneration;
    private Target target;
    private String token;

    NativeTvClient(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        saved = context.getSharedPreferences("simplecast-tv", Context.MODE_PRIVATE);
        String host = saved.getString("host", "");
        if (!host.isEmpty()) {
            try {
                int port = saved.getInt("port", 0);
                if (!local(InetAddress.getByName(host)) || port < 1 || port > 65535) throw new IllegalArgumentException();
                target = new Target(host, port, saved.getString("name", "SimpleCast TV")); token = saved.getString("token", null);
            } catch (Exception ignored) { disconnect(); }
        }
    }
    boolean paired() { return target != null && token != null; }
    String name() { return target == null ? "SimpleCast TV" : target.name; }
    void disconnect() { target = null; token = null; saved.edit().clear().apply(); }
    void discover(Consumer<List<Target>> listener) {
        stopDiscovery(); changed = listener; found.clear(); int generation = discoveryGeneration;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) { }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onServiceLost(NsdServiceInfo service) { main.post(() -> {
                if (generation != discoveryGeneration) return;
                found.remove(service.getServiceName()); publish();
            }); }
            @Override public void onServiceFound(NsdServiceInfo service) { main.post(() -> {
                if (generation != discoveryGeneration) return;
                if (queue.size() < 30) queue.add(service); resolveNext(generation);
            }); }
        };
        try { nsd.discoverServices(PlaybackContext.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException ignored) { }
    }
    private void publish() { if (changed != null) changed.accept(new ArrayList<>(found.values())); }
    private void resolveNext(int generation) {
        if (closed || generation != discoveryGeneration || resolving || queue.isEmpty()) return;
        NsdServiceInfo service = queue.remove(); resolving = true;
        try { nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo value, int error) { main.post(() -> next(null)); }
            @Override public void onServiceResolved(NsdServiceInfo value) { main.post(() -> next(value)); }
            private void next(NsdServiceInfo value) {
                resolving = false;
                if (generation != discoveryGeneration || closed) { resolveNext(discoveryGeneration); return; }
                if (value != null && value.getHost() != null && local(value.getHost()) && value.getPort() > 0) {
                    found.put(value.getServiceName(), new Target(value.getHost().getHostAddress(), value.getPort(), value.getServiceName())); publish();
                }
                resolveNext(generation);
            }
        }); } catch (RuntimeException ignored) { resolving = false; resolveNext(generation); }
    }
    void stopDiscovery() {
        discoveryGeneration++; changed = null; queue.clear();
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
            discovery = null;
        }
    }
    static Target manual(String input) throws Exception {
        String[] parts = input.trim().split(":");
        if (parts.length != 2 || !parts[0].matches("[0-9.]+")) throw new IllegalArgumentException();
        InetAddress address = InetAddress.getByName(parts[0]); int port = Integer.parseInt(parts[1]);
        if (!local(address) || port < 1 || port > 65535) throw new IllegalArgumentException();
        return new Target(address.getHostAddress(), port, "SimpleCast TV · " + address.getHostAddress());
    }
    private static boolean local(InetAddress address) { return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress(); }
    void pair(Target selected, String pin, Result result) {
        try {
            request(selected, null, "pair", new JSONObject().put("pin", pin), new Result() {
                @Override public void complete(JSONObject body) {
                    String value = body.optString("token");
                    if (value.isEmpty()) { result.failed("Réponse de la télé invalide."); return; }
                    target = selected; token = value;
                    saved.edit().putString("host", target.host).putInt("port", target.port).putString("name", target.name).putString("token", token).apply();
                    result.complete(body);
                }
                @Override public void failed(String message) { result.failed(message); }
            });
        } catch (Exception error) { result.failed("Code invalide."); }
    }
    void command(String command, JSONObject body, Result result) {
        if (!paired()) { result.failed("Connecte SimpleCast à l’appli SimpleCast TV."); return; }
        request(target, token, command, body, result);
    }
    private void request(Target selected, String credential, String command, JSONObject body, Result result) {
        if (closed) return;
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(selected.base() + "/" + command).openConnection();
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                if (credential != null) connection.setRequestProperty("X-SimpleCast-Token", credential);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
                int code = connection.getResponseCode();
                JSONObject response;
                try (var input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream()) {
                    if (input == null) throw new java.io.IOException();
                    response = new JSONObject(new String(SegmentDecoder.readLimited(input, 32768), StandardCharsets.UTF_8));
                }
                main.post(() -> {
                    if (closed) return;
                    if (code >= 200 && code < 300) result.complete(response);
                    else result.failed(response.optString("error", "La télé a refusé la commande."));
                });
            } catch (Exception error) { main.post(() -> { if (!closed) result.failed("Impossible de joindre SimpleCast TV. Ouvre l’appli sur la télé et vérifie le Wi-Fi."); }); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
    void close() { closed = true; stopDiscovery(); io.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
