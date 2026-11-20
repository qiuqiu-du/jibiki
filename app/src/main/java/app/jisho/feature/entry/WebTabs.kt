package app.jisho.feature.entry

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.jisho.R
import app.jisho.core.designsystem.ja
import kotlinx.coroutines.delay

/**
 * Entry page tabs (design-spec 3.5): the offline dictionaries, then two websites as backup.
 * [url] is the page for a word; [fallback], if any, is opened when that page doesn't exist;
 * [clutter]: the site's parts hidden as unrelated to the word (header with menu, search and
 * login, related-word lists, rankings, footer). Measured on both phone pages (2026-11-20).
 */
enum class EntryTab(
    val label: Int,
    val url: ((String) -> String)?,
    val fallback: ((String) -> String)? = null,
    val clutter: List<String> = emptyList(),
) {
    Offline(R.string.entry_tab_offline, null),
    // /word/食べる redirects to the entry; a spelling Kotobank lacks is a 404, then its search page
    // (which asks for one more tap on 検索).
    Kotobank(
        R.string.entry_tab_kotobank,
        { "https://kotobank.jp/word/" + Uri.encode(it) },
        { "https://kotobank.jp/search?q=" + Uri.encode(it) },
        // Top row (menu, logo, search), translation link, related words, bottom search, footer
        listOf("#menu", ".wordtop", "#retailkeyword", "#searchAreaWordBottom", "#ftWrap"),
    ),
    Weblio(
        R.string.entry_tab_weblio,
        { "https://www.weblio.jp/content/" + Uri.encode(it) },
        // Header (logo, login, search), the "〜とは？" title, related words and rankings,
        // copyright fold, page links, app promotion, footer
        clutter = listOf(
            "#header", "#content>h1", ".section-cards", ".division3.accordion", "ul.pageNav",
            ".weblio-apps", "footer.footer",
        ),
    ),
}

/**
 * The tabs as a pill floating over the bottom of the page (reachable with the thumb); the
 * selected one is filled.
 */
@Composable
fun FloatingTabs(selected: EntryTab, onSelect: (EntryTab) -> Unit) {
    val c = MaterialTheme.colorScheme
    // Theme colors only (indigo accent), so it matches both light and dark themes.
    Surface(shape = CircleShape, color = c.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            EntryTab.entries.forEach { t ->
                val on = t == selected
                val bg by animateColorAsState(if (on) c.primaryContainer else Color.Transparent, label = "tab")
                val fg by animateColorAsState(if (on) c.onPrimaryContainer else c.onSurfaceVariant, label = "tabText")
                Surface(onClick = { onSelect(t) }, shape = CircleShape, color = bg) {
                    Text(
                        stringResource(t.label),
                        Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge.ja(),
                        color = fg,
                    )
                }
            }
        }
    }
}

/** One WebView per web tab, kept while the entry page is open so switching tabs doesn't reload. */
class WebViews {
    val views = HashMap<EntryTab, WebView>()
    /** Loading progress of each tab's page, 0-100. */
    val progress = mutableStateMapOf<EntryTab, Int>()
    /** Called when a page is scrolled: true = down (reading on), false = up. */
    var onScroll: (Boolean) -> Unit = {}
}

@Composable
fun rememberWebViews(): WebViews {
    val web = remember { WebViews() }
    DisposableEffect(Unit) { onDispose { web.views.values.forEach { it.destroy() }; web.views.clear() } }
    return web
}

private var webViewWarm = false

/**
 * Starts the browser engine in the background of an entry page, once per app run. Its first
 * start takes a few hundred ms on the main thread; done on a tab tap, the whole screen
 * (tab row included) would stall before showing anything.
 */
@Composable
fun WarmUpWebView() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (webViewWarm) return@LaunchedEffect
        delay(500) // after the page has been drawn
        webViewWarm = true
        WebView(context.applicationContext).destroy()
    }
}

/**
 * The site's page for the word, without ads (see [AdBlock]). A page loads only when its tab is
 * first opened. [query]: the word's spellings, best first: a site may file a word under only
 * one of them (コトバンク: 嘗める, not 舐める), so on "not found" the next is tried, then the
 * site's search page for the first.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPage(tab: EntryTab, query: List<String>, web: WebViews, open: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!open && tab !in web.views) { // not opened yet: an empty page while swiping past
        Box(modifier.fillMaxSize())
        return
    }
    // A new tab first draws its selection and a progress bar; the WebView is made a frame
    // later, so the tab row never waits for it.
    var ready by remember(tab) { mutableStateOf(tab in web.views) }
    if (!ready) {
        LaunchedEffect(tab) { withFrameNanos {}; withFrameNanos {}; ready = true }
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        return
    }
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val cleanup = AdBlock.cleanupScript(tab.clutter)
    // The search page keeps its own search box (it asks for a tap on 検索): ads hidden only.
    val searchCleanup = AdBlock.cleanupScript()
    val fallback = tab.fallback?.invoke(query.first())
    val view = web.views.getOrPut(tab) {
        WebView(context).apply {
            setBackgroundColor(background) // until the page paints (default: black)
            // Hidden until the page has painted its first frame: before that a WebView shows
            // white, a flash in the dark theme.
            alpha = 0f
            setOnScrollChangeListener { _, _, y, _, oldY ->
                if (y > oldY + 4) web.onScroll(true) else if (y < oldY - 4) web.onScroll(false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    web.progress[tab] = newProgress
                }
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // In the dark theme, pages without a dark style of their own are darkened by the
            // browser (follows the window theme; API 33+).
            if (android.os.Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
            webViewClient = object : WebViewClient() { // keeps links inside the tab
                private var attempt = 0
                private val missing = HashSet<String>()

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame || response.statusCode != 404) return
                    val url = request.url.toString()
                    if (url == fallback) return
                    missing += url
                    attempt++
                    val next = query.getOrNull(attempt)?.let { tab.url!!(it) } ?: fallback ?: return
                    view.loadUrl(next)
                }

                // Ads (AdBlock): requests to ad hosts get an empty answer...
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (!request.isForMainFrame && AdBlock.isBlocked(request.url.host)) emptyResponse() else null

                // ...an ad link doesn't open...
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    AdBlock.isBlocked(request.url.host)

                // ...and leftover slots and dialogs are hidden as soon as the page shows.
                override fun onPageCommitVisible(view: WebView, url: String?) {
                    if (url in missing) return // a "not found" page: the next spelling is on its way
                    view.evaluateJavascript(if (url == fallback) searchCleanup else cleanup, null)
                    if (view.alpha < 1f) view.animate().alpha(1f).setDuration(150).start()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url in missing) return
                    view.evaluateJavascript(if (url == fallback) searchCleanup else cleanup, null)
                }
            }
            loadUrl(tab.url!!(query.first()))
        }
    }
    key(tab) {
        Box(modifier.fillMaxSize()) {
            AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize())
            val p = web.progress[tab] ?: 0
            if (p < 100) LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/**
 * What the web tabs try for a word, best first: its kanji spellings in dictionary order (the
 * sites file words under them: 余り even when the headword is あまり), then its readings.
 * [forms]: (kanji or null, reading) pairs.
 */
fun webSpellings(headword: String, forms: List<Pair<String?, String>>): List<String> =
    (forms.mapNotNull { it.first } + forms.map { it.second } + headword).distinct()

/** The reading in a dictionary item's title: たべる【食べる】 -> たべる, あい〔あひ〕【相】 -> あい; null if none. */
fun titleReading(title: String): String? {
    if ('【' !in title) return null
    return title.substringBefore('【').substringBefore('〔').replace(Regex("[‐–・\\s]"), "").ifEmpty { null }
}

/**
 * The word in a dictionary item's title: たべる【食べる】 -> 食べる, あい〔あひ〕【相】 -> 相,
 * ああ -> ああ. Marks for non-standard kanji (▽×▼) and alternative spellings are dropped.
 */
fun titleWord(title: String): String {
    val notation = Regex("【(.+?)】").find(title)?.groupValues?.get(1)
    val word = notation?.split('・', '／')?.first() ?: title.substringBefore('〔').substringBefore('［')
    return word.replace(Regex("[▽×▼‐–・\\s｛｝]"), "").ifEmpty { title }
}

/** What a blocked request gets: an empty body (a new stream each time; streams are read once). */
private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
