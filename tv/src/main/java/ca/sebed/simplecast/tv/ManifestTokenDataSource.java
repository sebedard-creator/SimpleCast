package ca.sebed.simplecast.tv;

import android.net.Uri;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import ca.sebed.simplecast.PlaybackContext;
import ca.sebed.simplecast.SegmentDecoder;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small authenticated manifests only. Video remains streamed through the normal data source. */
@UnstableApi
final class ManifestTokenDataSource implements DataSource {
    private final DataSource upstream;
    private final String mediaUrl;
    private final Map<String, String> context;
    private DataSource active;
    private Uri resolved;
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    ManifestTokenDataSource(DataSource upstream, String mediaUrl, Map<String, String> context) {
        this.upstream = upstream; this.mediaUrl = mediaUrl; this.context = context; active = upstream;
    }
    @Override public long open(DataSpec spec) throws IOException {
        active = upstream; resolved = null; responseHeaders = Collections.emptyMap();
        if (PlaybackContext.manifestToken(mediaUrl, spec.uri.toString(), context) == null
            || spec.httpMethod != DataSpec.HTTP_METHOD_GET) return upstream.open(spec);
        URL url = new URL(spec.uri.toString());
        // Follow redirects ourselves so a custom token cannot leak to another origin.
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
            context.forEach((key, value) -> { if (!key.equalsIgnoreCase("X-Token")) connection.setRequestProperty(key, value); });
            String token = PlaybackContext.manifestToken(mediaUrl, url.toString(), context);
            if (token != null) connection.setRequestProperty("X-Token", token);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirection sans adresse");
                    URL next = new URL(url, location);
                    if (!PlaybackContext.validUrl(next.toString())
                        || (url.getProtocol().equals("https") && !next.getProtocol().equals("https")))
                        throw new IOException("Redirection vidéo refusée");
                    url = next;
                    continue;
                }
                Map<String, List<String>> headers = new LinkedHashMap<>(connection.getHeaderFields()); headers.remove(null);
                if (code < 200 || code >= 300) {
                    throw new HttpDataSource.InvalidResponseCodeException(code, connection.getResponseMessage(), null,
                        headers, spec.buildUpon().setUri(url.toString()).build(), new byte[0]);
                }
                byte[] bytes;
                try (var input = connection.getInputStream()) { bytes = SegmentDecoder.readLimited(input, 1024 * 1024); }
                resolved = Uri.parse(url.toString()); responseHeaders = headers;
                active = new ByteArrayDataSource(bytes);
                return active.open(spec.buildUpon().setUri(resolved).build());
            } finally { connection.disconnect(); }
        }
        throw new IOException("Trop de redirections vidéo");
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException { return active.read(bytes, offset, length); }
    @Override public Uri getUri() { return resolved != null ? resolved : active.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return resolved != null ? responseHeaders : active.getResponseHeaders(); }
    @Override public void close() throws IOException { active.close(); }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
}
