package app.jisho.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import app.jisho.testing.TestPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for core/designsystem: pitch formatting and the body markup renderer. */
class DesignSystemTest {
    private val colors = BodyColors(Color.Blue, Color.Gray, Color.DarkGray, Color.Blue)
    private fun render(markup: String, style: LabelStyle = DefaultLabelStyle, onLink: (String) -> Unit = {}) =
        renderBody(markup, colors, style, onLink)

    // --- Pitch ---

    @Test fun pitchSingleAndMultiple() {
        assertEquals("⓪", formatAccent("0")); assertEquals("②", formatAccent("2"))
        assertEquals("⓪②", formatAccent("0,2")); assertEquals("⑳", formatAccent("20"))
    }

    @Test fun pitchCompoundAndMissing() {
        assertEquals("[1+1]", formatAccent("1+1")); assertEquals("[21]", formatAccent("21"))
        assertEquals("", formatAccent(null)); assertEquals("", formatAccent(""))
    }

    // --- Body markup ---

    @Test fun plainTextAndEntities() = assertEquals("A & B <c>", render("A &amp; B &lt;c&gt;").text)

    @Test fun rubyReadingsAreHidden() =
        assertEquals("固形の食物", render("<ruby>固形<rt>こけい</rt></ruby>の<ruby>食物<rt>しょくもつ</rt></ruby>").text)

    @Test fun paragraphsBecomeSingleLineBreaks() {
        assertEquals("一\n二\n三", render("<p>一</p><p>二</p><p><p>三</p></p>").text)
        assertEquals("a\nb", render("a<br>b<br>").text)
    }

    @Test fun gaijiImagesBecomePlaceholders() = assertEquals("x〓y", render("x<img src=\"daijisen/a.gif\">y").text)

    @Test fun spansKeepTextAndStyleIt() {
        val s = render("<span class=\"sense\">1</span> <span class=\"ex\">「例」</span>")
        assertEquals("1 「例」", s.text)
        assertTrue(s.spanStyles.size >= 2)
    }

    @Test fun linksAreClickableAndCarryTheirHref() {
        val clicked = mutableListOf<String>()
        val s = render("見よ→<a href=\"#12\">食う</a>・<a href=\"?のむ\">飲む</a>", onLink = clicked::add)
        val links = s.getLinkAnnotations(0, s.length)
        assertEquals(listOf("#12", "?のむ"), links.map { (it.item as LinkAnnotation.Clickable).tag })
        links.forEach { (it.item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(it.item) }
        assertEquals(listOf("#12", "?のむ"), clicked)
    }

    @Test fun labelsNeverShrinkOrBoldText() {
        // A smaller or synthetic-bold CJK glyph looks like a different character next to body text.
        for (style in LabelStyle.entries - LabelStyle.SmallBold) {
            val s = render("<span class=\"label\">光と影／光</span>明かり", style)
            assertTrue(style.name, s.spanStyles.all { it.item.fontSize == androidx.compose.ui.unit.TextUnit.Unspecified })
            assertTrue(style.name, s.spanStyles.all { it.item.fontWeight == null })
        }
    }

    @Test fun bracketLabelsAreWrapped() {
        assertEquals("〔類語〕〔光と影／光〕明かり",
            render("<span class=\"label\">類語</span><span class=\"label\">光と影／光</span>明かり", LabelStyle.Bracket).text)
    }

    @Test fun malformedMarkupDoesNotCrash() {
        assertEquals("ab", render("<b>a</i>b").text)
        assertEquals("text", render("</p>text<span>").text)
    }

    /** Renders a deterministic sample of real entries from every dictionary. */
    @Test
    fun realEntriesRenderWithoutLeftoverMarkup() {
        TestPacks.assumePresent()
        TestPacks.sharedDicts.forEach { d ->
            val ids = d.allIds()
            val sample = ids.filterIndexed { i, _ -> i % (ids.size / 3000 + 1) == 0 }
            sample.forEach { id ->
                val e = d.entry(id)!!
                val out = render(e.body)
                assertFalse("${d.dictId} #$id leaves a tag: ${out.text.take(80)}", Regex("<[a-z/]").containsMatchIn(out.text))
                assertFalse("${d.dictId} #$id leaves an entity", Regex("&(amp|lt|gt|quot);").containsMatchIn(out.text))
                assertEquals("${d.dictId} #$id link count", Regex("<a href=\"[^\"]+\"").findAll(e.body).count(),
                    out.getLinkAnnotations(0, out.length).size)
            }
        }
    }
}
