package ca.sebed.simplecast;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class SegmentDecoderTest {
    static byte[] transport() {
        byte[] bytes = new byte[188 * 9];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        for (int i = 0; i < bytes.length; i += 188) bytes[i] = 0x47;
        return bytes;
    }
    static byte[] pixelEnvelope(byte[] transport, int channels, int filter) throws IOException {
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream stream = new GZIPOutputStream(gzip)) { stream.write(transport); }
        byte[] packed = gzip.toByteArray();
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(envelope);
        payload.write("TIKTIKPX".getBytes(StandardCharsets.US_ASCII)); payload.writeInt(packed.length); payload.write(packed);
        int width = 7, height = (envelope.size() + width * 3 - 1) / (width * 3);
        byte[] rgb = Arrays.copyOf(envelope.toByteArray(), width * height * 3);
        ByteArrayOutputStream scanlines = new ByteArrayOutputStream();
        int stride = width * channels; byte[] previous = new byte[stride];
        for (int y = 0; y < height; y++) {
            byte[] row = new byte[stride];
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < 3; c++) row[x * channels + c] = rgb[(y * width + x) * 3 + c];
                if (channels == 4) row[x * 4 + 3] = (byte) 255;
            }
            scanlines.write(filter);
            for (int x = 0; x < stride; x++) {
                int left = x >= channels ? row[x - channels] & 255 : 0;
                int above = previous[x] & 255, corner = x >= channels ? previous[x - channels] & 255 : 0;
                int predicted = filter == 1 ? left : filter == 2 ? above : filter == 3 ? (left + above) / 2 : filter == 4 ? paeth(left, above, corner) : 0;
                scanlines.write((row[x] & 255) - predicted);
            }
            previous = row;
        }
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(deflated)) { stream.write(scanlines.toByteArray()); }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream hd = new DataOutputStream(header);
        hd.writeInt(width); hd.writeInt(height); hd.write(new byte[]{8, (byte)(channels == 4 ? 6 : 2), 0, 0, 0});
        chunk(png, "IHDR", header.toByteArray()); chunk(png, "IDAT", deflated.toByteArray()); chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream output, String type, byte[] bytes) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII); DataOutputStream data = new DataOutputStream(output);
        data.writeInt(bytes.length); data.write(name); data.write(bytes);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(bytes); data.writeInt((int) crc.getValue());
    }
    private static int paeth(int a, int b, int c) {
        int p = a + b - c, x = Math.abs(p - a), y = Math.abs(p - b), z = Math.abs(p - c);
        return x <= y && x <= z ? a : y <= z ? b : c;
    }
    @Test public void restoresRgbAndRgbaVideoWithEveryPngFilter() throws Exception {
        byte[] expected = transport();
        for (int channels : new int[]{3, 4}) for (int filter = 0; filter <= 4; filter++)
            assertArrayEquals("Channels " + channels + " filter " + filter, expected, SegmentDecoder.decode(pixelEnvelope(expected, channels, filter)));
    }
    @Test public void keepsNativeTransportAndFmp4BytesUnchanged() throws Exception {
        byte[] ts = transport(); assertSame(ts, SegmentDecoder.decode(ts));
        byte[] mp4 = new byte[]{0,0,0,8,'m','o','o','f'}; assertSame(mp4, SegmentDecoder.decode(mp4));
    }
    @Test public void extractsAppendedTransportAfterPngEnd() throws Exception {
        byte[] png = pixelEnvelope(transport(), 3, 0), ts = transport();
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream(); wrapped.write(png); wrapped.write(ts);
        assertArrayEquals(ts, SegmentDecoder.decode(wrapped.toByteArray()));
    }
    @Test public void rejectsCorruptAndTruncatedImages() throws Exception {
        byte[] png = pixelEnvelope(transport(), 3, 0); png[40] ^= 1;
        assertThrows(IOException.class, () -> SegmentDecoder.decode(png));
        byte[] good = pixelEnvelope(transport(), 3, 0);
        assertThrows(IOException.class, () -> SegmentDecoder.decode(Arrays.copyOf(good, 25)));
    }
    @Test public void boundsReadSizesAndRejectsForgedPixelPayloads() throws Exception {
        assertThrows(IOException.class, () -> SegmentDecoder.readLimited(new ByteArrayInputStream(new byte[11]), 10));
        assertThrows(IOException.class, () -> SegmentDecoder.decode(pixelEnvelope(new byte[]{1,2,3}, 3, 0)));
    }
    private static void little(ByteArrayOutputStream output, int value) {
        for (int i = 0; i < 4; i++) output.write(value >>> (i * 8) & 255);
    }
    @Test public void extractsTransportAppendedAtDeclaredWebpBoundary() throws Exception {
        byte[] ts = transport();
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        wrapped.write("RIFF".getBytes(StandardCharsets.US_ASCII)); little(wrapped, 16);
        wrapped.write("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)); little(wrapped, 4);
        wrapped.write(new byte[4]); wrapped.write(ts);
        assertArrayEquals(ts, SegmentDecoder.decode(wrapped.toByteArray()));
        byte[] forged = wrapped.toByteArray(); forged[24 + 188 * 2] = 0;
        assertThrows(IOException.class, () -> SegmentDecoder.decode(forged));
        byte[] truncated = Arrays.copyOf(wrapped.toByteArray(), 20);
        assertThrows(IOException.class, () -> SegmentDecoder.decode(truncated));
    }
    @Test public void keepsSupportingTransportInsideWebpExif() throws Exception {
        byte[] ts = transport();
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        wrapped.write("RIFF".getBytes(StandardCharsets.US_ASCII)); little(wrapped, ts.length + 12);
        wrapped.write("WEBPEXIF".getBytes(StandardCharsets.US_ASCII)); little(wrapped, ts.length); wrapped.write(ts);
        assertArrayEquals(ts, SegmentDecoder.decode(wrapped.toByteArray()));
    }
}
