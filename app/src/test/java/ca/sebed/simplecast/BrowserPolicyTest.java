package ca.sebed.simplecast;

import org.junit.Test;
import org.junit.After;
import static org.junit.Assert.*;

public class BrowserPolicyTest {
    @After public void restoreList() { BrowserPolicy.loadAdHosts(""); }
    @Test public void readsHostsFilesAndMatchesDomainBoundariesWithoutTouchingVideoTokens() {
        BrowserPolicy.loadAdHosts("# source notice\n127.0.0.1 ads.test tracker.test # note\n0.0.0.0 banners.test\n127.0.0.1 localhost\n::1 localhost\nnot-a-hosts-entry\n");
        for (String url : new String[]{"https://ads.test/script.js", "https://cdn.ads.test/banner", "https://TRACKER.TEST./pixel", "https://banners.test/"})
            assertTrue(url, BrowserPolicy.blockedResource(url));
        for (String url : new String[]{"https://notads.test/", "https://ads.test.player.test/", "http://localhost/player",
            "https://cdn.test/live.m3u8?token=ads.test", "https://player.test/embed"}) assertFalse(url, BrowserPolicy.blockedResource(url));
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "https://ads.test/", true, true, false));
        assertFalse(BrowserPolicy.blockedNavigation("https://site.test/", "https://ads.test/", true, true, false, false));
        assertTrue(BrowserPolicy.blockedNavigation("https://site.test/", "intent://ads.test/", true, true, false, false));
    }
    @Test public void blocksObservedAdHostsAndTheirSubdomains() {
        assertTrue(BrowserPolicy.blockedResource("https://debitm.org/22/script"));
        assertTrue(BrowserPolicy.blockedResource("https://YD.OSCINEABETTAL.CFD/loader"));
        assertTrue(BrowserPolicy.blockedResource("https://debitm.org./script"));
        assertTrue(BrowserPolicy.blockedResource("https://acscdn.com/script/aclib.js"));
        assertTrue(BrowserPolicy.blockedResource("https://al5sm.com/tag.min.js"));
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
