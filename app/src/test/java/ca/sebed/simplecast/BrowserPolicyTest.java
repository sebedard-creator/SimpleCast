package ca.sebed.simplecast;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrowserPolicyTest {
    @Test public void blocksObservedAdHostsAndTheirSubdomains() {
        assertTrue(BrowserPolicy.blockedResource("https://debitm.org/22/script"));
        assertTrue(BrowserPolicy.blockedResource("https://YD.OSCINEABETTAL.CFD/loader"));
        assertTrue(BrowserPolicy.blockedResource("https://debitm.org./script"));
    }
    @Test public void preservesPlayersStreamsAndUnrelatedHostnames() {
        for (String url : new String[]{"https://player.test/player", "https://sts.dervlin.me/scripts/v1/embed.min.js",
                "https://cdn.test/live.m3u8", "https://notdebitm.org/script", "https://debitm.org.example.com/",
                "https://cdn.test/video?referer=debitm.org"}) assertFalse(url, BrowserPolicy.blockedResource(url));
    }
    @Test public void blocksAutomaticTopLevelTakeoversButAllowsIframeNavigation() {
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "https://unwanted.test/", true, false, false));
        assertFalse(BrowserPolicy.blockedNavigation("https://site.test/", "https://player.test/player", false, false, false));
    }
    @Test public void allowsNormalClicksHttpRedirectsAndSameSiteScripts() {
        assertFalse(BrowserPolicy.blockedNavigation("https://site.test/", "https://player.test/player", true, true, false));
        assertFalse(BrowserPolicy.blockedNavigation("http://site.test/", "https://www.site.test/", true, false, true));
        assertFalse(BrowserPolicy.blockedNavigation("https://site.test/", "https://site.test/index.php", true, false, false));
    }
    @Test public void blocksAdNavigationAndExternalAppSchemesRegardlessOfGesture() {
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "https://debitm.org/", true, true, true));
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "intent://unwanted.test/", true, true, false));
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "javascript:alert(1)", false, true, false));
    }
}
