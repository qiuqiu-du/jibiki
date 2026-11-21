package app.jisho

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.SummaryPlace
import app.jisho.core.data.SummaryPrefs
import app.jisho.core.lemmatizer.KuromojiLemmatizer
import app.jisho.feature.search.HandwritingRecognizer
import app.jisho.feature.search.InkPoint
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Performance targets from design-spec §7, measured on the phone with the imported
 * packs (no UI interaction). Run: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DevicePerformanceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JishoApp

    /**
     * An empty activity on screen while measuring. MagicOS freezes a process with nothing in the
     * foreground after about a minute (cgroup freezer, even with the screen on), and the run then
     * hangs; the activity is the blank one Compose's test manifest declares, so no app UI runs.
     */
    @get:Rule val foreground = ActivityScenarioRule(ComponentActivity::class.java)

    private fun p95(samples: List<Double>) = samples.sorted()[(samples.size * 0.95).toInt().coerceAtMost(samples.size - 1)]

    @Test
    fun searchAndEntryMeetTargets() = runBlocking {
        app.dataPacks.prepareCore() // the test run reinstalled the app: core.db is rebuilt first
        assumeTrue("push the data packs first (scripts/push-datapacks.sh)", app.dataPacks.isReady())
        keepScreenOn()
        // With the screen off the phone dozes and starves this process of CPU: the run then
        // takes many minutes and every number is wrong, so stop at once instead.
        val power = app.getSystemService(android.os.PowerManager::class.java)
        assertTrue("screen is off: keep the phone awake while measuring (scrcpy --stay-awake)", power.isInteractive)
        // Kuromoji build time and heap, measured on a fresh tokenizer (the app's may be loaded already).
        System.gc()
        val heapBefore = usedHeapMb()
        val kuromojiMs = timeMs { KuromojiLemmatizer(Tokenizer()).lemmatize("食べなかった") }
        val kuromojiHeap = usedHeapMb() - heapBefore
        val kuromoji = app.kuromoji.load()
        // Same setup as the app in the default mode: lemmatizer and sentence segmenter on.
        val repo = DictionaryRepository(app.dataPacks, { app.combined }, { kuromoji })
        val queries = listOf("た", "か", "し", "あ", "たべ", "たべる", "せんせい", "がっこう", "日", "日本",
            "食べ", "taberu", "kyou", "はし", "かみ", "する", "コーヒー", "こうこう", "ん", "べんきょう",
            // inflected forms and a sentence go through the lemmatizers
            "食べなかった", "いった", "読めない", "tabenakatta", "してる", "お読みになりました",
            "かった", "食べさせられなかった", "昨日は本を読まなかった", "行かなきゃ")
        val progress = java.io.File(app.filesDir, "perf-progress.txt").also { it.writeText("start\n") }
        repo.search("warm") // open the packs once (cold start cost is measured separately)
        progress.appendText("warm\n")
        val perQuery = queries.associateWith { q -> (1..5).map { timeMs { repo.search(q) } }.also { progress.appendText("$q ${it.max()}\n") } }
        val searchMs = perQuery.values.flatten()
        val ids = queries.mapNotNull { repo.search(it).words.firstOrNull()?.entryId }
        val entryMs = ids.map { id -> timeMs { repo.entry(id) } }
        // Written to the app's files dir; scripts/test-device.sh prints it (phone logs are encrypted).
        val report = "search: n=${searchMs.size} median=${"%.1f".format(searchMs.sorted()[searchMs.size / 2])} ms " +
            "p95=${"%.1f".format(p95(searchMs))} ms max=${"%.1f".format(searchMs.max())} ms\n" +
            "entry:  n=${entryMs.size} median=${"%.1f".format(entryMs.sorted()[entryMs.size / 2])} ms " +
            "p95=${"%.1f".format(p95(entryMs))} ms max=${"%.1f".format(entryMs.max())} ms\n"
        // Worst case for search: a Japanese and a Chinese line on every row (settings → 一行释义).
        val every = SummaryPlace.entries.toSet()
        val withLines = DictionaryRepository(app.dataPacks, { app.combined }, { kuromoji }, summaries = { SummaryPrefs(every, every) })
        withLines.search("warm")
        progress.appendText("lines warm\n")
        val linesMs = queries.flatMap { q -> (1..5).map { timeMs { withLines.search(q) } }.also { progress.appendText("lines $q ${it.max()}\n") } }
        val linesReport = "search with both one-line meanings on every row: median=${"%.1f".format(linesMs.sorted()[linesMs.size / 2])} ms " +
            "p95=${"%.1f".format(p95(linesMs))} ms max=${"%.1f".format(linesMs.max())} ms\n"
        val slowest = perQuery.entries.sortedByDescending { it.value.max() }.take(6)
            .joinToString("  ") { (q, t) -> "$q=${"%.0f".format(t.max())}" }
        val lemmaReport = "kuromoji: build+first call ${"%.0f".format(kuromojiMs)} ms, heap +${"%.0f".format(kuromojiHeap)} MB\n"
        java.io.File(app.filesDir, "perf-report.txt").writeText(report + linesReport + lemmaReport + "slowest queries (max ms): $slowest\n")
        assertTrue("search p95 ${p95(searchMs)} ms > 50", p95(searchMs) <= 50)
        assertTrue("entry p95 ${p95(entryMs)} ms > 150", p95(entryMs) <= 150)
        assertTrue("search with lines p95 ${p95(linesMs)} ms > 50", p95(linesMs) <= 50)
    }

    /**
     * Handwriting cold start in a fresh process: asking Google Play services whether the model
     * is downloaded, then the first recognition (creates the recognizer and loads the model into
     * memory), then a warm recognition. Skipped when the model is not downloaded.
     */
    @Test
    fun handwritingModelLoadTimes() = runBlocking {
        val r = HandwritingRecognizer()
        var ready = false
        val checkMs = timeMs { ready = r.isModelReady() }
        assumeTrue("download the handwriting model in the app first", ready)
        val checkAgainMs = timeMs { r.isModelReady() }
        val stroke = listOf((0..20).map { i -> InkPoint(50f + i * 20f, 300f, i * 10L) })
        var first = emptyList<String>()
        val firstMs = timeMs { first = r.recognize(stroke, 600f, 600f, "") }
        val warmMs = (1..5).map { timeMs { r.recognize(stroke, 600f, 600f, "") } }
        r.close()
        java.io.File(app.filesDir, "handwriting-report.txt").writeText(
            "handwriting: model check ${"%.0f".format(checkMs)} ms (again ${"%.0f".format(checkAgainMs)} ms), " +
                "first recognition ${"%.0f".format(firstMs)} ms, warm median ${"%.0f".format(warmMs.sorted()[2])} ms; " +
                "first candidates ${first.take(3)}\n",
        )
        assertTrue(first.isNotEmpty())
    }

    /** The blank activity holds the screen on (this window only; no system setting changes). */
    private fun keepScreenOn() = foreground.scenario.onActivity {
        it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun usedHeapMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1e6 }

    private inline fun timeMs(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }
}
