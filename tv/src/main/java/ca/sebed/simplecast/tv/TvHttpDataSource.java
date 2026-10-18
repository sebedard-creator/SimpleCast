package ca.sebed.simplecast.tv;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.datasource.cronet.CronetUtil;
import com.google.android.gms.net.CronetProviderInstaller;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.chromium.net.CronetEngine;

/** Share Chromium's network engine without bundling it or routing video through the phone. */
@UnstableApi
final class TvHttpDataSource implements DataSource {
    private static final ExecutorService callbacks = Executors.newFixedThreadPool(2);
    private static Future<CronetEngine> engine;
    private final Map<String, String> headers;
    private final List<TransferListener> listeners = new ArrayList<>();
    private DataSource active;

    private TvHttpDataSource(Map<String, String> headers) { this.headers = headers; }

    static synchronized void warmUp(Context context) {
        if (engine != null) return;
        Context app = context.getApplicationContext();
        engine = callbacks.submit(() -> {
            try {
                Tasks.await(CronetProviderInstaller.installProvider(app), 10, TimeUnit.SECONDS);
                return CronetUtil.buildCronetEngine(app);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return null;
            } catch (Exception unavailable) { return null; }
        });
    }
    static DataSource.Factory factory(Context context, Map<String, String> headers) {
        warmUp(context);
        Map<String, String> properties = Map.copyOf(headers);
        return () -> new TvHttpDataSource(properties);
    }
    @Override public long open(DataSpec spec) throws IOException {
        CronetEngine chromium = null;
        try { chromium = engine.get(12, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Chargement vidéo interrompu", interrupted);
        } catch (Exception unavailable) { /* Devices without Play services keep native HTTP support. */ }
        active = chromium == null
            ? new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                .setUserAgent(headers.get("User-Agent")).setConnectTimeoutMs(12000).setReadTimeoutMs(12000).createDataSource()
            : new CronetDataSource.Factory(chromium, callbacks).setDefaultRequestProperties(headers)
                .setUserAgent(headers.get("User-Agent")).setConnectionTimeoutMs(12000).setReadTimeoutMs(12000).createDataSource();
        listeners.forEach(active::addTransferListener);
        return active.open(spec);
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException { return active.read(bytes, offset, length); }
    @Override public Uri getUri() { return active == null ? null : active.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return active == null ? Collections.emptyMap() : active.getResponseHeaders(); }
    @Override public void close() throws IOException {
        if (active != null) { try { active.close(); } finally { active = null; } }
    }
    @Override public void addTransferListener(TransferListener listener) {
        listeners.add(listener); if (active != null) active.addTransferListener(listener);
    }
}
