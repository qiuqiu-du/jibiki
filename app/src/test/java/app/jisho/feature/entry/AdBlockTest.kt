package app.jisho.feature.entry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which hosts the web tabs refuse to load. */
class AdBlockTest {
    @Test fun adHostsAndTheirSubdomainsAreBlocked() {
        for (h in listOf(
            "securepubads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "fundingchoicesmessages.google.com", // the "watch an ad to continue" dialog
            "cdn.btloader.com", // ad-block detection
            "prebid-asia.creativecdn.com", "ib.adnxs.com", "DOUBLECLICK.NET.",
        )) assertTrue(h, AdBlock.isBlocked(h))
    }

    @Test fun siteAndCommonHostsStayLoaded() {
        for (h in listOf(
            "kotobank.jp", "www.weblio.jp", "cdn.weblio.jp", "www.google.com", "fonts.googleapis.com",
            "www.gstatic.com", "notdoubleclick.net", "doubleclick.net.example.com", null,
        )) assertFalse(h.toString(), AdBlock.isBlocked(h))
    }

    @Test fun cleanupScriptIsSelfContained() {
        val js = AdBlock.cleanupScript(EntryTab.Weblio.clutter)
        assertTrue(js.startsWith("(function()") && js.trimEnd().endsWith("})();"))
        assertTrue(".fc-message-root" in js && "display:none!important" in js)
        // Site selectors go in, quoted for a JS string; each site only gets its own.
        assertTrue("#content>h1" in js && "#menu" !in js)
        assertTrue("#menu" in AdBlock.cleanupScript(EntryTab.Kotobank.clutter))
    }
}
