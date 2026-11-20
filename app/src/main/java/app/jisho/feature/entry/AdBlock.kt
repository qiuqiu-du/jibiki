package app.jisho.feature.entry

/**
 * Ad blocking for the web tabs (コトバンク, Weblio), like a browser ad blocker: requests to ad
 * and ad-tracking hosts are not loaded, and what they leave behind (empty slots, the "watch an
 * ad to continue" dialog) is hidden. Lists measured on both sites' phone pages (2026-11-20);
 * when a site changes ad providers, add the new hosts or selectors here.
 */
object AdBlock {
    /** A host is blocked when it is one of these or a subdomain of one. */
    private val HOSTS = setOf(
        // Google ads, ad measurement and analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "googletagmanager.com", "google-analytics.com", "analytics.google.com", "adtrafficquality.google",
        "adservice.google.com",
        // Google Funding Choices: the "watch an ad to unlock" dialog and ad-block recovery messages
        "fundingchoicesmessages.google.com",
        // Ad-block detection and recovery (would bring the ads back or nag)
        "btloader.com", "ad-delivery.net", "dns-finder.com",
        // Header bidding and ad networks seen on Weblio
        "browsiprod.com", "richaudience.com", "socdm.com", "ladsp.com", "creativecdn.com", "adtdp.com",
        "gmossp-sp.jp", "mediago.io", "uncn.jp", "impact-ad.jp", "4dex.io", "casalemedia.com",
        "criteo.com", "criteo.net", "adnxs.com", "rubiconproject.com", "pubmatic.com", "flux-cdn.com",
        "flux.jp", "adkernel.com", "openx.net",
        // Other common Japanese and global ad networks
        "amazon-adsystem.com", "taboola.com", "outbrain.com", "yads.yahoo.co.jp", "microad.jp",
        "microad.net", "i-mobile.co.jp", "adingo.jp", "kyujinbox.com",
    )

    fun isBlocked(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** Ad slots and dialogs to hide, in case something still renders them. */
    private val HIDDEN = listOf(
        // Google ad slots (both sites)
        "[id^=\"div-gpt-ad\"]", "[id^=\"google_ads_iframe\"]", "ins.adsbygoogle",
        "iframe[src*=\"googlesyndication\"]", "iframe[src*=\"doubleclick\"]",
        // Funding Choices dialog (rewarded-ad wall) and its overlay
        ".fc-message-root", ".fc-ab-root", ".fc-dialog-overlay",
        // Weblio: ad blocks, Browsi slots, the fixed overlay ad at the bottom
        ".ad-blank", ".ad_fixed_imgHigh", "[id^=\"browsi_\"]",
        // コトバンク: in-article ads, job ads, the fixed ad at the bottom
        ".sp-word-ad", ".sp-word-ad-fixed", ".kyujinbox-ad", "#fixed_footer",
    )

    private fun css(extra: List<String>) = (HIDDEN + extra).joinToString(",") + "{display:none!important}" +
        // A dialog may have locked scrolling; give it back.
        "html,body{overflow:auto!important}"

    /**
     * Run after each page commit: adds the hiding style once (ads plus [extra], a site's own
     * selectors for parts unrelated to the word), and keeps removing dialogs that a script
     * inserts later (a MutationObserver), restoring scrolling they lock.
     */
    fun cleanupScript(extra: List<String> = emptyList()): String = """
        (function() {
          if (window.__jishoAdBlock) return; window.__jishoAdBlock = true;
          var s = document.createElement('style'); s.textContent = ${jsString(css(extra))};
          (document.head || document.documentElement).appendChild(s);
          function clean() {
            document.querySelectorAll('.fc-message-root,.fc-ab-root').forEach(function(e) { e.remove(); });
            [document.documentElement, document.body].forEach(function(e) { if (e) e.style.overflow = ''; });
          }
          clean();
          new MutationObserver(clean).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()

    private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
