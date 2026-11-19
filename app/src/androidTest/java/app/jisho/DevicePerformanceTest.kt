package app.jisho

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jisho.core.data.DictionaryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Performance targets from design-spec §7, measured on the phone with the imported
 * packs (no UI interaction). Run: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DevicePerformanceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JishoApp

    private fun p95(samples: List<Double>) = samples.sorted()[(samples.size * 0.95).toInt().coerceAtMost(samples.size - 1)]

    @Test
    fun searchAndEntryMeetTargets() = runBlocking {
        assumeTrue("import the data packs in the app first", app.dataPacks.isReady())
        val repo = DictionaryRepository(app.dataPacks)
        val queries = listOf("た", "か", "し", "あ", "たべ", "たべる", "せんせい", "がっこう", "日", "日本",
            "食べ", "taberu", "kyou", "はし", "かみ", "する", "コーヒー", "こうこう", "ん", "べんきょう")
        repo.search("warm") // open the packs once (cold start cost is measured separately)
        val perQuery = queries.associateWith { q -> (1..5).map { timeMs { repo.search(q) } } }
        val searchMs = perQuery.values.flatten()
        val ids = queries.mapNotNull { repo.search(it).words.firstOrNull()?.entryId }
        val entryMs = ids.map { id -> timeMs { repo.entry(id) } }
        // Written to the app's files dir; scripts/test-device.sh prints it (phone logs are encrypted).
        val report = "search: n=${searchMs.size} median=${"%.1f".format(searchMs.sorted()[searchMs.size / 2])} ms " +
            "p95=${"%.1f".format(p95(searchMs))} ms max=${"%.1f".format(searchMs.max())} ms\n" +
            "entry:  n=${entryMs.size} median=${"%.1f".format(entryMs.sorted()[entryMs.size / 2])} ms " +
            "p95=${"%.1f".format(p95(entryMs))} ms max=${"%.1f".format(entryMs.max())} ms\n"
        val slowest = perQuery.entries.sortedByDescending { it.value.max() }.take(6)
            .joinToString("  ") { (q, t) -> "$q=${"%.0f".format(t.max())}" }
        java.io.File(app.filesDir, "perf-report.txt").writeText(report + "slowest queries (max ms): $slowest\n")
        assertTrue("search p95 ${p95(searchMs)} ms > 50", p95(searchMs) <= 50)
        assertTrue("entry p95 ${p95(entryMs)} ms > 150", p95(entryMs) <= 150)
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }
}
