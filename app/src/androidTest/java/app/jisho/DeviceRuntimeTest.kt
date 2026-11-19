package app.jisho

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.jisho.core.designsystem.BodyColors
import app.jisho.core.designsystem.renderBody
import app.jisho.core.japanese.Romaji
import app.jisho.core.japanese.SearchKey
import org.junit.Assert.assertEquals
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
