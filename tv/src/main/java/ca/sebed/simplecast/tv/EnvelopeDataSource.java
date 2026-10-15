package ca.sebed.simplecast.tv;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import ca.sebed.simplecast.SegmentDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Decode supported image envelopes on the TV; ordinary video remains streamed. */
@UnstableApi
final class EnvelopeDataSource implements DataSource {
    private final DataSource upstream;
    private DataSource active;
    EnvelopeDataSource(DataSource upstream) { this.upstream = upstream; active = upstream; }
    @Override public long open(DataSpec spec) throws IOException {
        active = upstream;
        long size = upstream.open(spec);
        boolean image = upstream.getResponseHeaders().entrySet().stream().anyMatch(e -> e.getKey() != null
            && e.getKey().equalsIgnoreCase("content-type") && e.getValue().stream().anyMatch(v -> v.startsWith("image/")));
        if (!image) return size;
        try {
            byte[] decoded = SegmentDecoder.decode(SegmentDecoder.readLimited(new InputStream() {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException { return upstream.read(bytes, offset, length); }
                @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
            }, SegmentDecoder.MAX_BYTES));
            upstream.close();
            active = new ByteArrayDataSource(decoded);
            return active.open(spec.buildUpon().setLength(C.LENGTH_UNSET).build());
        } catch (IOException error) { upstream.close(); throw error; }
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException { return active.read(bytes, offset, length); }
    @Override public Uri getUri() { return active.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void close() throws IOException { active.close(); }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
}
