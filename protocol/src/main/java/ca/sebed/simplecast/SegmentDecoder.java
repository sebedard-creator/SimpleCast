package ca.sebed.simplecast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Restores MPEG-TS from public image transport envelopes; never decrypts DRM or transcodes. */
public final class SegmentDecoder {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    private SegmentDecoder() { }

    public static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - result.size()) throw new IOException("Réponse vidéo trop volumineuse.");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    public static boolean isTransportStream(byte[] bytes) {
        return bytes.length >= 376 && bytes[0] == 0x47 && bytes[188] == 0x47;
    }
    public static byte[] decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Segment vidéo trop volumineux.");
        if (isTransportStream(bytes)) return bytes;
        if (starts(bytes, 0, PNG)) return decodePng(bytes);
        if (text(bytes, 0, 4).equals("RIFF") && text(bytes, 8, 4).equals("WEBP")) {
            long declaredEnd = uint32le(bytes, 4) + 8;
            if (declaredEnd < 12 || declaredEnd > bytes.length) throw new IOException("Enveloppe WebP incomplète.");
            int end = (int) declaredEnd;
            if (end < bytes.length) {
                byte[] appended = Arrays.copyOfRange(bytes, end, bytes.length);
                if (appended.length >= 188 * 3 && appended.length % 188 == 0
                    && isTransportStream(appended) && appended[188 * 2] == 0x47) return appended;
            }
            for (int offset = 12; offset + 8 <= end; ) {
                long size = uint32le(bytes, offset + 4);
                if (size + (size & 1) > end - offset - 8) throw new IOException("Enveloppe WebP incomplète.");
                if (text(bytes, offset, 4).equals("EXIF")) {
                    byte[] data = Arrays.copyOfRange(bytes, offset + 8, offset + 8 + (int) size);
                    if (isTransportStream(data)) return data;
                }
                offset += 8 + (int) size + ((int) size & 1);
            }
            throw new IOException("Cette enveloppe WebP ne contient pas de vidéo reconnue.");
        }
        // Older transport envelopes carry a short public marker and an optional gzip body.
        for (String marker : new String[]{"TIKTIKRAW", "TIKTIKTSGZ"}) {
            byte[] tag = marker.getBytes(StandardCharsets.US_ASCII);
            for (int offset = 0; offset <= bytes.length - tag.length; offset++) {
                if (!starts(bytes, offset, tag)) continue;
                byte[] body = Arrays.copyOfRange(bytes, offset + tag.length, bytes.length);
                if (marker.endsWith("GZ")) body = gunzip(body);
                if (isTransportStream(body)) return body;
            }
        }
        // Normal fMP4 or encrypted HLS bytes pass through unchanged.
        return bytes;
    }
    private static byte[] decodePng(byte[] bytes) throws IOException {
        int width = 0, height = 0, channels = 0, end = -1;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        boolean header = false;
        for (int offset = 8; offset + 12 <= bytes.length; ) {
            long size = uint32(bytes, offset);
            if (size > bytes.length - offset - 12) throw new IOException("Enveloppe PNG incomplète.");
            int count = (int) size;
            String type = text(bytes, offset + 4, 4);
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, 4 + count);
            if (crc.getValue() != uint32(bytes, offset + 8 + count)) throw new IOException("Enveloppe PNG corrompue.");
            if (type.equals("IHDR")) {
                if (header || count != 13) throw new IOException("En-tête PNG invalide.");
                long w = uint32(bytes, offset + 8), h = uint32(bytes, offset + 12);
                int depth = bytes[offset + 16] & 255, color = bytes[offset + 17] & 255;
                if (w == 0 || h == 0 || w > 8192 || h > 8192 || depth != 8 || (color != 2 && color != 6)
                    || bytes[offset + 18] != 0 || bytes[offset + 19] != 0 || bytes[offset + 20] != 0)
                    throw new IOException("Ce format d’enveloppe PNG n’est pas pris en charge.");
                channels = color == 6 ? 4 : 3;
                if ((w * channels + 1) * h > MAX_BYTES) throw new IOException("Enveloppe PNG trop volumineuse.");
                width = (int) w; height = (int) h; header = true;
            } else if (type.equals("IDAT")) {
                if (!header) throw new IOException("En-tête PNG manquant.");
                compressed.write(bytes, offset + 8, count);
            } else if (type.equals("IEND")) { end = offset + 12 + count; break; }
            offset += 12 + count;
        }
        if (end < 0) throw new IOException("Enveloppe PNG incomplète.");
        if (end < bytes.length) {
            byte[] appended = Arrays.copyOfRange(bytes, end, bytes.length);
            if (isTransportStream(appended)) return appended;
        }
        if (!header || channels == 0) throw new IOException("En-tête PNG manquant.");
        int stride = width * channels, expected = (stride + 1) * height;
        byte[] raw;
        try (InputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            raw = readLimited(input, expected);
        }
        if (raw.length != expected) throw new IOException("Pixels PNG incomplets.");
        byte[] rgb = new byte[width * height * 3], previous = new byte[stride];
        int source = 0, destination = 0;
        for (int y = 0; y < height; y++) {
            int filter = raw[source++] & 255;
            if (filter > 4) throw new IOException("Filtre PNG invalide.");
            byte[] row = new byte[stride];
            for (int x = 0; x < stride; x++) {
                int left = x >= channels ? row[x - channels] & 255 : 0;
                int above = previous[x] & 255;
                int corner = x >= channels ? previous[x - channels] & 255 : 0;
                int predictor = filter == 1 ? left : filter == 2 ? above : filter == 3 ? (left + above) / 2
                    : filter == 4 ? paeth(left, above, corner) : 0;
                row[x] = (byte) ((raw[source++] & 255) + predictor);
            }
            for (int x = 0; x < stride; x += channels) {
                rgb[destination++] = row[x]; rgb[destination++] = row[x + 1]; rgb[destination++] = row[x + 2];
            }
            previous = row;
        }
        byte[] marker = "TIKTIKPX".getBytes(StandardCharsets.US_ASCII);
        if (!starts(rgb, 0, marker) || rgb.length < 12) throw new IOException("Cette image PNG ne contient pas de vidéo reconnue.");
        long size = uint32(rgb, 8);
        if (size < 2 || size > rgb.length - 12) throw new IOException("Taille du contenu vidéo invalide.");
        byte[] decoded = gunzip(Arrays.copyOfRange(rgb, 12, 12 + (int) size));
        if (!isTransportStream(decoded)) throw new IOException("Le contenu décodé n’est pas un segment MPEG-TS.");
        return decoded;
    }
    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) { return readLimited(input, MAX_BYTES); }
    }
    private static int paeth(int a, int b, int c) {
        int p = a + b - c, da = Math.abs(p - a), db = Math.abs(p - b), dc = Math.abs(p - c);
        return da <= db && da <= dc ? a : db <= dc ? b : c;
    }
    private static boolean starts(byte[] bytes, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > bytes.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[offset + i] != prefix[i]) return false;
        return true;
    }
    private static String text(byte[] bytes, int offset, int count) {
        return offset < 0 || offset + count > bytes.length ? "" : new String(bytes, offset, count, StandardCharsets.US_ASCII);
    }
    private static long uint32(byte[] bytes, int offset) {
        return ((bytes[offset] & 255L) << 24) | ((bytes[offset + 1] & 255L) << 16)
            | ((bytes[offset + 2] & 255L) << 8) | (bytes[offset + 3] & 255L);
    }
    private static long uint32le(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
            | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
}
