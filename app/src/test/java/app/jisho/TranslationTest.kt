package app.jisho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Interface text comes in Chinese (values/), Japanese (values-ja/) and English (values-en/):
 * every translatable string and plural has both translations, with the same placeholders.
 * Strings marked translatable="false" (dictionary terms, formats) stay as they are.
 */
class TranslationTest {
    private val res = File("src/main/res")
    private val string = Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val plurals = Regex("""<plurals name="([a-z0-9_]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
    private val item = Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d+\$[sd]""")

    private fun file(dir: String) = File(res, "$dir/strings.xml").readText()

    /** Translatable strings: key -> text. */
    private fun strings(dir: String) = string.findAll(file(dir))
        .filter { "translatable=\"false\"" !in it.groupValues[2] }
        .associate { it.groupValues[1] to it.groupValues[3] }

    /** Plurals: key -> (quantity -> text). */
    private fun plurals(dir: String) = plurals.findAll(file(dir))
        .associate { p -> p.groupValues[1] to item.findAll(p.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] } }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    @Test fun everyStringIsTranslatedWithTheSamePlaceholders() {
        val source = strings("values")
        assertTrue(source.size > 100)
        for (lang in listOf("values-ja", "values-en")) {
            val translated = strings(lang)
            assertEquals("$lang: keys", source.keys.sorted(), translated.keys.sorted())
            for ((key, text) in source) {
                assertEquals("$lang/$key", placeholders(text), placeholders(translated.getValue(key)))
            }
        }
    }

    /** Every quantity of a plural uses the source's placeholders, and "other" is always there. */
    @Test fun everyPluralIsTranslatedWithTheSamePlaceholders() {
        val source = plurals("values")
        assertTrue(source.isNotEmpty())
        for (lang in listOf("values", "values-ja", "values-en")) {
            val translated = plurals(lang)
            assertEquals("$lang: keys", source.keys.sorted(), translated.keys.sorted())
            for ((key, items) in translated) {
                assertTrue("$lang/$key needs an \"other\" item", "other" in items)
                val expected = placeholders(source.getValue(key).getValue("other"))
                items.forEach { (quantity, text) -> assertEquals("$lang/$key/$quantity", expected, placeholders(text)) }
            }
        }
    }
}
