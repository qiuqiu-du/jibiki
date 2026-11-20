package app.jisho

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.jisho.core.designsystem.BodyColors
import app.jisho.core.designsystem.renderBody
import app.jisho.core.japanese.Romaji
import app.jisho.core.japanese.SearchKey
import androidx.test.platform.app.InstrumentationRegistry
import app.jisho.core.lemmatizer.CombinedLemmatizer
import app.jisho.core.lemmatizer.LemmaValidator
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs pure-Kotlin code on Android's runtime. The JVM tests cannot catch platform
 * differences (Android uses ICU regex and its own java.text), which once crashed
 * every search: `(?U)` is valid on desktop Java but rejected on Android.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRuntimeTest {
    private fun lines(name: String) = javaClass.classLoader!!.getResource(name)!!.readText().lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun searchKeyMatchesPythonOnAndroid() {
        val failures = lines("search_key_vectors.tsv").mapNotNull { line ->
            val (input, expected) = line.split('\t')
            SearchKey.of(input).takeIf { it != expected }?.let { "$input -> $it (python: $expected)" }
        }
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun romajiOnAndroid() {
        assertEquals("たべる", Romaji.toHiragana("taberu"))
        assertEquals("ちょっと", Romaji.toHiragana("chotto"))
    }

    /**
     * Kuromoji loads its dictionary from Java resources inside the APK, and the rule table
     * from assets: run the whole lemma test set on the phone in the default mode.
     */
    @Test
    fun lemmaTestSetOnAndroid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JishoApp
        assumeTrue("import the data packs in the app first", app.dataPacks.isReady())
        val core = app.dataPacks.openCore()
        val validator = LemmaValidator(core)
        val lemmatizer = CombinedLemmatizer({ app.kuromoji.load() }, app.rules)
        val failures = lines("lemma_cases.tsv").mapNotNull { line ->
            val (input, lemma) = line.split('\t')
            val found = validator.validate(lemmatizer.lemmatize(SearchKey.of(input))).map { v ->
                core.entry(v.entryId)!!.forms.flatMap { listOfNotNull(it.kanji, it.reading) }
            }
            if (found.none { lemma in it }) "$input -> $lemma" else null
        }
        core.close()
        assertEquals(failures.take(10).joinToString("\n"), 0, failures.size)
    }

    @Test
    fun bodyRendererRegexesWorkOnAndroid() {
        val s = renderBody(
            "<p><span class=\"sense\">1</span><ruby>食<rt>た</rt></ruby>べる<a href=\"#3\">x</a></p>",
            BodyColors(Color.Blue, Color.Gray, Color.Gray, Color.Blue),
        ) {}
        assertEquals("1食べるx", s.text)
        assertEquals(1, s.getLinkAnnotations(0, s.length).size)
    }
}
