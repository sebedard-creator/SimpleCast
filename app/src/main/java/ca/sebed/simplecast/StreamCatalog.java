package ca.sebed.simplecast;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded in-memory catalog; never persists tokens, browsing history or cookies. */
public final class StreamCatalog {
    public static final class Stream {
        public final String url;
        public String mime;
        public String frame;
        public String player;
        public String title;
        public Boolean live;
        public boolean fallback, master;
        private long lastSeen;
        private final Set<String> associations = new LinkedHashSet<>();
        Stream(String url, String mime, String frame, String player, String title, Boolean live) {
            this.url = url; this.mime = mime; this.frame = frame; this.player = player;
            this.title = title; this.live = live;
            associate(frame, player);
        }
        private void associate(String frame, String player) {
            if (frame != null && !frame.isEmpty() && associations.size() < 150)
                associations.add(frame + "\n" + (player == null ? "" : player));
        }
        public boolean matches(String frame, String player) {
            return associations.contains(frame + "\n" + player) || associations.contains(frame + "\n");
        }
        public boolean exactMatch(String frame, String player) {
            return associations.contains(frame + "\n" + player);
        }
        public String format() {
            String type = mime.toLowerCase(Locale.ROOT);
            if (type.contains("mpegurl")) return "HLS";
            if (type.contains("dash")) return "DASH";
            if (type.contains("webm")) return "WebM";
            return "MP4";
        }
    }
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private long observations;

    public static String mediaMime(String url, String contentType) {
        if (!validUrl(url)) return null;
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl")
                || type.equals("audio/mpegurl") || type.equals("audio/x-mpegurl"))
            return "application/x-mpegURL";
        if (type.equals("application/dash+xml")) return type;
        try {
            String path = new URI(url).getPath().toLowerCase(Locale.ROOT);
            if (path.matches(".*\\.(ts|m4s|cmfv|cmfa)$")
                || path.matches(".*/(init|initialization)([-_][^/]*)?\\.(mp4|m4v)$")) return null;
            if (type.equals("video/mp4") || type.equals("video/webm")) return type;
            if (path.endsWith(".m3u8")) return "application/x-mpegURL";
            if (path.endsWith(".mpd")) return "application/dash+xml";
            if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
            if (path.endsWith(".webm")) return "video/webm";
        } catch (Exception ignored) { }
        return null; // Segments (.ts/.m4s), thumbnails and blob URLs aren't standalone videos.
    }

    public static boolean validUrl(String value) {
        return PlaybackContext.validUrl(value);
    }

    public Stream add(String url, String type, String frame, String player, String title, Boolean live) {
        String mime = mediaMime(url, type);
        if (mime == null) return null;
        Stream current = streams.get(url);
        if (current == null) {
            if (streams.size() >= 150) {
                Stream oldest = streams.values().stream().min(java.util.Comparator.comparingLong(s -> s.lastSeen)).orElseThrow();
                streams.remove(oldest.url);
            }
            current = new Stream(url, mime, frame, player, title, live);
            streams.put(url, current);
        } else {
            current.mime = mime;
            current.associate(frame, player);
            // Network observations mustn't replace a known player with a weaker guess.
            if ((current.player == null || current.player.isEmpty()) || (player != null && !player.isEmpty())) {
                if (frame != null && !frame.isEmpty()) current.frame = frame;
                if (player != null && !player.isEmpty()) current.player = player;
                if (title != null && !title.isEmpty()) current.title = title;
            }
            if (live != null) current.live = live;
        }
        current.lastSeen = ++observations;
        return current;
    }
    /** Exact player associations come first; renewed URLs precede older observations. */
    public List<Stream> ranked(String frame, String player) {
        List<Stream> result = new ArrayList<>();
        for (Stream stream : streams.values())
            if (frame == null || stream.matches(frame, player)) result.add(stream);
        result.sort(java.util.Comparator.<Stream>comparingInt(s -> frame != null && s.exactMatch(frame, player) ? 0 : 1)
            .thenComparingInt(s -> frame != null && s.fallback ? 0 : 1)
            .thenComparing(java.util.Comparator.comparingLong((Stream s) -> s.lastSeen).reversed()));
        return result;
    }
    /** Prefer a site's alternate encoding, then the active source and a full HLS master. */
    public List<Stream> forPlayback(String frame, String player, String currentSource) {
        List<Stream> result = ranked(frame, player);
        // Stable sorting retains observation order when these stronger signals are equal.
        result.sort(java.util.Comparator.<Stream>comparingInt(s -> frame != null && s.exactMatch(frame, player) ? 0 : 1)
            .thenComparingInt(s -> frame != null && s.fallback ? 0 : 1)
            .thenComparingInt(s -> s.url.equals(currentSource) ? 0 : 1)
            .thenComparingInt(s -> s.master ? 0 : 1)
            .thenComparingInt(s -> s.format().equals("HLS") ? 0 : 1));
        return result;
    }
    public List<Stream> all() { return new ArrayList<>(streams.values()); }
    public int size() { return streams.size(); }
    public void clear() { streams.clear(); observations = 0; }
}
