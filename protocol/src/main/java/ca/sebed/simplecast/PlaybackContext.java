package ca.sebed.simplecast;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request context for native TV playback; cookies and account authorization stay in the browser. */
public final class PlaybackContext {
    public static final String SERVICE_TYPE = "_simplecast._tcp.";
    private PlaybackContext() { }

    public static boolean validUrl(String value) {
        if (value == null || value.length() > 16384) return false;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (Exception error) { return false; }
    }
    public static Map<String, String> headers(String frame, Map<String, String> observed, String userAgent) {
        Map<String, String> result = new LinkedHashMap<>();
        observed.forEach((key, value) -> {
            if (value == null || value.length() > 4096 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return;
            if (key.equalsIgnoreCase("referer") && validUrl(value)) result.put("Referer", value);
            if (key.equalsIgnoreCase("origin") && validUrl(value)) result.put("Origin", value);
            if (key.equalsIgnoreCase("user-agent")) result.put("User-Agent", value);
            if (key.equalsIgnoreCase("x-token") && !value.isEmpty()) result.put("X-Token", value);
        });
        if (!result.containsKey("Referer") && validUrl(frame)) result.put("Referer", frame);
        if (!result.containsKey("Origin") && result.containsKey("Referer")) {
            URI uri = URI.create(result.get("Referer"));
            result.put("Origin", uri.getScheme() + "://" + uri.getRawAuthority());
        }
        if (!result.containsKey("User-Agent") && userAgent != null && userAgent.length() <= 4096
            && userAgent.indexOf('\r') < 0 && userAgent.indexOf('\n') < 0) result.put("User-Agent", userAgent);
        return result;
    }

    /** A manifest token belongs only to its original HTTP origin, never to segment CDNs. */
    public static String manifestToken(String mediaUrl, String requestUrl, Map<String, String> headers) {
        String token = headers.get("X-Token");
        if (token == null || !validUrl(mediaUrl) || !validUrl(requestUrl)) return null;
        URI media = URI.create(mediaUrl), request = URI.create(requestUrl);
        if (!media.getScheme().equalsIgnoreCase(request.getScheme())
            || !media.getHost().equalsIgnoreCase(request.getHost()) || port(media) != port(request)) return null;
        String path = request.getPath().toLowerCase(java.util.Locale.ROOT);
        return requestUrl.equals(mediaUrl) || path.endsWith(".m3u8") || path.endsWith(".mpd") ? token : null;
    }
    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
