package ca.sebed.simplecast;

import java.net.URI;
import java.util.Locale;

/** Small local filter for popup transports observed on supported video pages. */
public final class BrowserPolicy {
    private static final String[] AD_HOSTS = {"debitm.org", "oscineabettal.cfd"};
    private BrowserPolicy() { }

    public static boolean blockedResource(String url) {
        String host = host(url);
        for (String domain : AD_HOSTS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    public static boolean blockedNavigation(String current, String target, boolean mainFrame, boolean gesture, boolean redirect) {
        if (!StreamCatalog.validUrl(target) || blockedResource(target)) return true;
        // Embedded players may navigate freely. Keep normal clicks and HTTP redirects working.
        if (!mainFrame || gesture || redirect || !StreamCatalog.validUrl(current)) return false;
        return !host(current).equals(host(target));
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        } catch (IllegalArgumentException | NullPointerException error) { return ""; }
    }
}
