package app.jisho.core.designsystem

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

data class BodyColors(
    val accent: Color,
    val muted: Color,
    val example: Color,
    val link: Color,
    val labelBackground: Color = Color.Unspecified,
)

/** How dictionary labels (類語, 使い方, thesaurus categories) are drawn. Body text size is never reduced. */
enum class LabelStyle { SmallBold, Accent, Tinted, Bracket }

/**
 * Chosen by comparing all styles on the phone (2026-11-20): Bracket keeps body size and weight,
 * stays distinct from blue links (Accent did not), and is lighter than Tinted.
 */
val DefaultLabelStyle = LabelStyle.Bracket

/** Inline content ids of gaiji pictures start with this, followed by the picture's name. */
const val GAIJI_PREFIX = "gaiji:"

/**
 * Renders body markup (Architecture.md §9, body_format 1) into an AnnotatedString.
 * Ruby readings (<rt>) are hidden in this version; links call [onLink] with the raw href.
 */
fun renderBody(
    markup: String,
    colors: BodyColors,
    labelStyle: LabelStyle = DefaultLabelStyle,
    onLink: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        val tag = Regex("<(/?)([a-z0-9]+)([^>]*)>")
        val attr = Regex("""(\w+)="([^"]*)"""")
        val open = ArrayDeque<Pair<String, Boolean>>() // tag name, pushed a style/link
        val closingText = ArrayDeque<String>() // text appended when a bracketed label closes
        var hidden = 0
        var badge: Pair<String, StringBuilder>? = null // class and text of an open number badge
        var pos = 0
        var last = '\n' // last appended char; '\n' at start so no leading blank line

        fun put(s: String) {
            if (s.isEmpty()) return
            append(s); last = s.last()
        }

        fun newline() {
            if (last != '\n') put("\n")
        }

        fun text(raw: String) {
            if (hidden > 0 || raw.isEmpty()) return
            badge?.let { it.second.append(unescape(raw.trim())); return }
            // Source indentation (spaces before a block) is not part of the text.
            put(unescape(if (last == '\n') raw.trimStart(' ', '\t') else raw))
        }

        for (m in tag.findAll(markup)) {
            text(markup.substring(pos, m.range.first))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            if (closing) {
                if (name == "rt") { hidden = maxOf(0, hidden - 1); continue }
                if (name == "p") newline()
                // Pop to the matching open tag.
                while (open.isNotEmpty()) {
                    val (n, pushed) = open.removeLast()
                    if (n == "label") put(closingText.removeLast())
                    if (n == "badge") badge?.let { (cls, t) ->
                        badge = null
                        appendInlineContent(BADGE_PREFIX + cls + ":" + t, badgeFallback(cls, t.toString()))
                        last = '\u0000'
                    }
                    if (pushed) pop()
                    if (n == name || (n == "label" || n == "badge") && name == "span") break
                }
                continue
            }
            val attrs = attr.findAll(m.groupValues[3]).associate { it.groupValues[1] to it.groupValues[2] }
            when (name) {
                "br" -> put("\n")
                // Gaiji picture: drawn by the Text's inline content (see gaijiContent); 〓 if missing.
                "img" -> attrs["src"]?.let { appendInlineContent(GAIJI_PREFIX + it, "〓"); last = '〓' }
                "rt" -> hidden++
                "p" -> { newline(); open.addLast(name to false) }
                "b" -> { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); open.addLast(name to true) }
                "i" -> { pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)); open.addLast(name to true) }
                "sup" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em)); open.addLast(name to true) }
                "sub" -> { pushStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em)); open.addLast(name to true) }
                "span" -> if (attrs["class"] in BADGE_CLASSES) {
                    // Section and sense numbers drawn by the app (see Badges.kt).
                    badge = attrs["class"]!! to StringBuilder()
                    open.addLast("badge" to false)
                } else if (attrs["class"] == "label") {
                    // Labels keep the body's size and weight: a smaller or synthetic-bold CJK glyph
                    // looks like a different character next to normal text.
                    val (style, open1, close1) = when (labelStyle) {
                        LabelStyle.SmallBold -> Triple(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 0.9.em), "", "")
                        LabelStyle.Accent -> Triple(SpanStyle(color = colors.accent), "", "　")
                        LabelStyle.Tinted -> Triple(SpanStyle(color = colors.accent, background = colors.labelBackground), "\u2009", "\u2009")
                        LabelStyle.Bracket -> Triple(SpanStyle(color = colors.muted), "〔", "〕")
                    }
                    pushStyle(style)
                    put(open1)
                    closingText.addLast(close1)
                    open.addLast("label" to true)
                } else {
                    val style = when (attrs["class"]) {
                        "pos" -> SpanStyle(color = colors.muted)
                        "sense" -> SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)
                        "ex" -> SpanStyle(color = colors.example)
                        "note", "mark" -> SpanStyle(color = colors.muted)
                        // Chinese in Japanese-Chinese dictionaries: Chinese glyph forms (费, 吃).
                        "gloss" -> SpanStyle(fontWeight = FontWeight.SemiBold, localeList = ChineseLocale)
                        "zh" -> SpanStyle(localeList = ChineseLocale)
                        else -> null
                    }
                    if (style != null) pushStyle(style)
                    open.addLast(name to (style != null))
                }
                "a" -> {
                    val href = attrs["href"]
                    if (href.isNullOrEmpty()) {
                        open.addLast(name to false)
                    } else {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = href,
                                styles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.None)),
                                linkInteractionListener = { onLink(href) },
                            ),
                        )
                        open.addLast(name to true)
                    }
                }
                else -> open.addLast(name to false) // ruby and unknown tags: keep text only
            }
        }
        text(markup.substring(pos))
        while (open.isNotEmpty()) {
            val (n, pushed) = open.removeLast()
            if (n == "label") put(closingText.removeLast())
            if (pushed) pop()
        }
    }.let { trimTrailingNewlines(it) }

private fun trimTrailingNewlines(s: AnnotatedString): AnnotatedString {
    var end = s.length
    while (end > 0 && s.text[end - 1] == '\n') end--
    return if (end == s.length) s else s.subSequence(0, end)
}

private fun unescape(s: String): String =
    if ('&' !in s) s
    else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&amp;", "&")
