package ca.sebed.simplecast;

import java.net.URI;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Local hostname filtering; never blocks by substrings in a video's path or token. */
public final class BrowserPolicy {
    private static final Set<String> OBSERVED_AD_HOSTS = Set.of("debitm.org", "oscineabettal.cfd", "acscdn.com", "al5sm.com");
    private static volatile Set<String> adHosts = OBSERVED_AD_HOSTS;
    private static final Pattern DOMAIN = Pattern.compile("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private BrowserPolicy() { }

    public static void loadAdHosts(String text) {
        Set<String> hosts = new HashSet<>(OBSERVED_AD_HOSTS);
        for (String line : text.split("\\R")) {
            String[] fields = line.split("#", 2)[0].trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (fields.length < 2 || !(fields[0].equals("127.0.0.1") || fields[0].equals("0.0.0.0"))) continue;
            for (int i = 1; i < fields.length; i++) {
                String candidate = fields[i];
                if (candidate.length() <= 253 && DOMAIN.matcher(candidate).matches()) hosts.add(candidate);
            }
        }
        adHosts = Set.copyOf(hosts);
    }

    public static boolean blockedResource(String url) {
        String host = host(url);
        Set<String> hosts = adHosts;
        while (!host.isEmpty()) {
            if (hosts.contains(host)) return true;
            int dot = host.indexOf('.');
            if (dot < 0) break;
            host = host.substring(dot + 1);
        }
        return false;
    }

    public static boolean blockedNavigation(String current, String target, boolean mainFrame, boolean gesture, boolean redirect) {
        return blockedNavigation(current, target, mainFrame, gesture, redirect, true);
    }
    public static boolean blockedNavigation(String current, String target, boolean mainFrame, boolean gesture, boolean redirect, boolean filterEnabled) {
        if (!StreamCatalog.validUrl(target) || (filterEnabled && blockedResource(target))) return true;
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
