package ca.sebed.simplecast;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites all playable HLS resources so no child request escapes the selected relay session. */
public final class HlsPlaylist {
    public enum Kind { PLAYLIST, SEGMENT, KEY, INIT, FILE }
    public interface Mapper { String map(String absoluteUrl, Kind kind) throws IOException; }
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?<![A-Z0-9_-])URI=\"([^\"]*)\"");
    private HlsPlaylist() { }
    public static String rewrite(String content, String base, Mapper mapper) throws IOException {
        if (!content.startsWith("#EXTM3U")) throw new IOException("Le serveur n’a pas fourni un manifeste HLS valide.");
        StringBuilder output = new StringBuilder(); boolean nextPlaylist = false;
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = mapper.map(resolve(base, trimmed), nextPlaylist ? Kind.PLAYLIST : Kind.SEGMENT);
                nextPlaylist = false;
            } else if (trimmed.startsWith("#EXT-X-STREAM-INF:")) { nextPlaylist = true; }
            if (trimmed.startsWith("#EXT")) {
                Kind kind = trimmed.startsWith("#EXT-X-KEY:") || trimmed.startsWith("#EXT-X-SESSION-KEY:") ? Kind.KEY
                    : trimmed.startsWith("#EXT-X-MAP:") || (trimmed.startsWith("#EXT-X-PRELOAD-HINT:") && trimmed.contains("TYPE=MAP")) ? Kind.INIT
                    : trimmed.startsWith("#EXT-X-MEDIA:") || trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF:") || trimmed.startsWith("#EXT-X-RENDITION-REPORT:") ? Kind.PLAYLIST
                    : trimmed.startsWith("#EXT-X-SESSION-DATA:") ? Kind.FILE : Kind.SEGMENT;
                Matcher matcher = URI_ATTRIBUTE.matcher(line); StringBuffer rewritten = new StringBuffer();
                while (matcher.find()) {
                    String local = mapper.map(resolve(base, matcher.group(1)), kind);
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement("URI=\"" + local + "\""));
                }
                matcher.appendTail(rewritten); line = rewritten.toString();
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }
    private static String resolve(String base, String value) throws IOException {
        try {
            String resolved = new URI(base).resolve(value).toString();
            if (!StreamCatalog.validUrl(resolved)) throw new IOException("Adresse de ressource HLS non prise en charge.");
            return resolved;
        } catch (IllegalArgumentException | java.net.URISyntaxException exception) {
            throw new IOException("Adresse de ressource HLS invalide.", exception);
        }
    }
}
