package ca.sebed.simplecast;

import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;

/** LAN-only, session-scoped relay. HTTP clients can access registered media, never arbitrary URLs. */
public final class LocalRelay extends NanoHTTPD {
    public interface Cookies { String get(String url); }
    private static final int MAX_RESOURCES = 4096;
    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    private final String address;
    private final String session = randomId();
    private final String source;
    private final Map<String, String> headers;
    private final Cookies cookies;
    private final Map<String, Resource> resources = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, String> resourceIds = new LinkedHashMap<>();
    private final Map<String, Media> cache = new LinkedHashMap<>(8, 0.75f, true);
    private final Semaphore requests = new Semaphore(3);
    private int cachedBytes;
    private volatile String lastError = "";
    private volatile boolean closed;
    private String root;

    private static final class Resource {
        final String url; final HlsPlaylist.Kind kind;
        Resource(String url, HlsPlaylist.Kind kind) { this.url = url; this.kind = kind; }
    }
    private static final class Media {
        final byte[] bytes; final String mime;
        Media(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
    }
    public LocalRelay(String address, String source, String frame, Map<String, String> observed, Cookies cookies) throws IOException {
        super(address, 0); this.address = address; this.source = source; this.cookies = cookies;
        if (!StreamCatalog.validUrl(source)) throw new IOException("Adresse vidéo invalide.");
        Map<String, String> context = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : observed.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT), value = entry.getValue();
            if ((key.equals("referer") || key.equals("origin") || key.equals("user-agent") || key.equals("authorization"))
                && value != null && value.length() <= 8192 && !value.contains("\r") && !value.contains("\n")) context.put(key, value);
        }
        if (StreamCatalog.validUrl(frame)) {
            context.putIfAbsent("referer", frame);
            context.putIfAbsent("origin", origin(frame));
        }
        context.putIfAbsent("user-agent", "SimpleCast/0.2");
        headers = Collections.unmodifiableMap(context);
    }
    public void startMedia(boolean hls) throws IOException {
        start(15000, true);
        root = register(source, hls ? HlsPlaylist.Kind.PLAYLIST : HlsPlaylist.Kind.FILE);
    }
    public String rootUrl() { return root; }
    public String lastError() { return lastError; }
    private synchronized String register(String url, HlsPlaylist.Kind kind) throws IOException {
        if (closed || !StreamCatalog.validUrl(url)) throw new IOException("Ressource vidéo invalide.");
        String key = kind + "\n" + url, id = resourceIds.get(key);
        if (id == null) {
            if (resources.size() >= MAX_RESOURCES) {
                String oldest = resources.keySet().iterator().next();
                Resource removed = resources.remove(oldest); resourceIds.remove(removed.kind + "\n" + removed.url);
            }
            id = randomId(); resources.put(id, new Resource(url, kind)); resourceIds.put(key, id);
        } else { resources.get(id); }
        String filename = kind == HlsPlaylist.Kind.PLAYLIST ? "index.m3u8" : kind == HlsPlaylist.Kind.SEGMENT ? "segment.ts" : kind == HlsPlaylist.Kind.INIT ? "init.mp4" : "media";
        return "http://" + address + ":" + getListeningPort() + "/" + session + "/" + id + "/" + filename;
    }
    @Override public Response serve(IHTTPSession request) {
        if (request.getMethod() == Method.OPTIONS) return cors(newFixedLengthResponse(status(204), "text/plain", ""));
        if (request.getMethod() != Method.GET && request.getMethod() != Method.HEAD)
            return cors(newFixedLengthResponse(status(405), "text/plain", "Method not allowed"));
        String[] path = request.getUri().split("/");
        Resource resource;
        synchronized (this) { resource = path.length == 4 && path[1].equals(session) ? resources.get(path[2]) : null; }
        if (resource == null || closed) return cors(newFixedLengthResponse(status(404), "text/plain", "Media not found"));
        if (!requests.tryAcquire()) return cors(newFixedLengthResponse(status(503), "text/plain", "Relay busy"));
        try {
            if (resource.kind == HlsPlaylist.Kind.PLAYLIST) return playlist(resource);
            if (resource.kind == HlsPlaylist.Kind.SEGMENT || resource.kind == HlsPlaylist.Kind.INIT || resource.kind == HlsPlaylist.Kind.KEY)
                return binary(resource, request.getHeaders().get("range"));
            return progressive(resource, request.getHeaders().get("range"));
        } catch (IOException | RuntimeException error) {
            lastError = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "Erreur de lecture du relais.";
            return cors(newFixedLengthResponse(status(502), "text/plain", "Media relay failed"));
        } finally { requests.release(); }
    }
    private Response playlist(Resource resource) throws IOException {
        HttpURLConnection connection = connect(resource.url, null);
        try {
            requireSuccess(connection);
            String body;
            try (InputStream input = input(connection)) {
                body = new String(SegmentDecoder.readLimited(input, 1024 * 1024), StandardCharsets.UTF_8);
            }
            String rewritten = HlsPlaylist.rewrite(body, connection.getURL().toString(), this::register);
            return cors(newFixedLengthResponse(status(200), "application/vnd.apple.mpegurl", rewritten));
        } finally { connection.disconnect(); }
    }
    private Response binary(Resource resource, String range) throws IOException {
        Media media;
        synchronized (cache) {
            media = cache.get(resource.url + resource.kind);
            if (media == null) {
                HttpURLConnection connection = connect(resource.url, null);
                try {
                    requireSuccess(connection);
                    byte[] bytes;
                    try (InputStream input = input(connection)) { bytes = SegmentDecoder.readLimited(input, SegmentDecoder.MAX_BYTES); }
                    if (resource.kind == HlsPlaylist.Kind.SEGMENT) bytes = SegmentDecoder.decode(bytes);
                    String mime = SegmentDecoder.isTransportStream(bytes) ? "video/mp2t" : mime(connection);
                    if (bytes.length >= 8 && (ascii(bytes, 4, 4).equals("ftyp") || ascii(bytes, 4, 4).equals("styp") || ascii(bytes, 4, 4).equals("moof"))) mime = "video/mp4";
                    media = new Media(bytes, mime);
                    if (bytes.length <= CACHE_BYTES) {
                        while (cachedBytes + bytes.length > CACHE_BYTES && !cache.isEmpty()) {
                            String first = cache.keySet().iterator().next(); cachedBytes -= cache.remove(first).bytes.length;
                        }
                        cache.put(resource.url + resource.kind, media); cachedBytes += bytes.length;
                    }
                } finally { connection.disconnect(); }
            }
        }
        int length = media.bytes.length;
        long[] bounds = parseRange(range, length);
        if (bounds == null) {
            Response response = newFixedLengthResponse(status(416), "text/plain", "Invalid media range");
            response.addHeader("Content-Range", "bytes */" + length); return cors(response);
        }
        int start = (int) bounds[0], count = (int) (bounds[1] - bounds[0] + 1);
        Response response = newFixedLengthResponse(status(range == null ? 200 : 206), media.mime, new ByteArrayInputStream(media.bytes, start, count), count);
        response.addHeader("Accept-Ranges", "bytes");
        if (range != null) response.addHeader("Content-Range", "bytes " + bounds[0] + "-" + bounds[1] + "/" + length);
        return cors(response);
    }
    private Response progressive(Resource resource, String range) throws IOException {
        HttpURLConnection connection = connect(resource.url, range);
        try {
            requireSuccess(connection);
            InputStream body = new FilterInputStream(input(connection)) {
                @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
            };
            long length = connection.getContentLengthLong();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) length = -1;
            Response response = length >= 0 ? newFixedLengthResponse(status(connection.getResponseCode()), mime(connection), body, length)
                : newChunkedResponse(status(connection.getResponseCode()), mime(connection), body);
            for (String name : new String[]{"Content-Range", "Accept-Ranges"}) {
                String value = connection.getHeaderField(name); if (value != null) response.addHeader(name, value);
            }
            return cors(response);
        } catch (IOException | RuntimeException error) { connection.disconnect(); throw error; }
    }
    private HttpURLConnection connect(String url, String range) throws IOException {
        if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IOException("Plage vidéo invalide.");
        for (int redirect = 0; redirect < 8; redirect++) {
            if (!StreamCatalog.validUrl(url)) throw new IOException("Redirection vidéo non prise en charge.");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("Cache-Control", "no-cache");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!header.getKey().equals("authorization") || origin(source).equals(origin(url)))
                    connection.setRequestProperty(header.getKey(), header.getValue());
            }
            String cookie = cookies == null ? null : cookies.get(url);
            if (cookie != null && cookie.length() <= 16384 && !cookie.contains("\r") && !cookie.contains("\n")) connection.setRequestProperty("Cookie", cookie);
            if (range != null) connection.setRequestProperty("Range", range);
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirection vidéo incomplète.");
                    url = connection.getURL().toURI().resolve(location).toString(); connection.disconnect(); continue;
                }
                return connection;
            } catch (IOException | java.net.URISyntaxException error) {
                connection.disconnect(); throw new IOException("Impossible de contacter le serveur vidéo.", error);
            }
        }
        throw new IOException("Trop de redirections du serveur vidéo.");
    }
    private static void requireSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Le serveur vidéo répond " + code + ".");
    }
    private static InputStream input(HttpURLConnection connection) throws IOException {
        InputStream body = connection.getInputStream();
        return "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(body) : body;
    }
    private static String mime(HttpURLConnection connection) {
        String type = connection.getContentType(); return type == null ? "application/octet-stream" : type.split(";", 2)[0];
    }
    public static String origin(String url) {
        URI uri = URI.create(url);
        int port = uri.getPort(); boolean standard = port < 0 || (port == 443 && uri.getScheme().equalsIgnoreCase("https")) || (port == 80 && uri.getScheme().equalsIgnoreCase("http"));
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port);
    }
    static long[] parseRange(String range, int length) {
        if (range == null) return new long[]{0, length - 1L};
        if (length == 0 || !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) return null;
        try {
            String[] parts = range.substring(6).split("-", -1);
            long start, end;
            if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); if (suffix == 0) return null; start = Math.max(0, length - suffix); end = length - 1L; }
            else { start = Long.parseLong(parts[0]); end = parts[1].isEmpty() ? length - 1L : Math.min(Long.parseLong(parts[1]), length - 1L); }
            return start >= length || end < start ? null : new long[]{start, end};
        } catch (NumberFormatException error) { return null; }
    }
    private static Response cors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type");
        response.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges");
        response.addHeader("Cache-Control", "no-store"); return response;
    }
    private static Response.IStatus status(int code) {
        return new Response.IStatus() {
            @Override public int getRequestStatus() { return code; }
            @Override public String getDescription() { return code + " Relay"; }
        };
    }
    private static String ascii(byte[] bytes, int offset, int count) { return new String(bytes, offset, count, StandardCharsets.US_ASCII); }
    private static String randomId() { return UUID.randomUUID().toString().replace("-", ""); }
    public synchronized void closeMedia() {
        closed = true; stop(); resources.clear(); resourceIds.clear();
        synchronized (cache) { cache.clear(); cachedBytes = 0; }
    }
}
