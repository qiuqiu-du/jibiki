package app.jisho.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Number badges drawn by the app instead of dictionary pictures or Unicode look-alikes:
 *  - `<span class="box-b">一</span>`: black-box section number (大辞泉・明鏡・新明解: part of speech)
 *  - `<span class="box-w">一</span>`: white-box section number (sub-sections)
 *  - `<span class="num-c">21</span>`: circled sense number, 1-99
 * Black and white boxes form a pair: same shape and size, told apart by fill (solid / outline).
 */
val BADGE_CLASSES = setOf("box-b", "box-w", "num-c")

/** Inline content ids of badges: prefix + class + ":" + text. */
const val BADGE_PREFIX = "badge:"

/** Text used where a badge can't be drawn (plain-text places, missing inline content). */
fun badgeFallback(cls: String, text: String): String = when (cls) {
    "box-b" -> "［$text］"
    "box-w" -> "〔$text〕"
    else -> "($text)"
}

private const val GAP = 0.15f

private const val INLINE_TAG = "androidx.compose.foundation.text.inlineContent"

/** Inline content for every badge in [text] (from [renderBody]). */
fun badgeContent(text: AnnotatedString): Map<String, InlineTextContent> =
    text.getStringAnnotations(INLINE_TAG, 0, text.length).map { it.item }.filter { it.startsWith(BADGE_PREFIX) }
        .distinct().associateWith { id ->
            val (cls, label) = id.removePrefix(BADGE_PREFIX).split(":", limit = 2)
            // Box width in em (two kanji such as 十一 need a wider box); the slot adds a small gap
            // so the next character doesn't touch it.
            val box = if (label.length >= 2 && cls != "num-c") 1.6f else 1.05f
            InlineTextContent(Placeholder((box + GAP).em, 1.05.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize()) {
                    Badge(cls, label, Modifier.fillMaxHeight().fillMaxWidth(box / (box + GAP)))
                }
            }
        }

/**
 * Accent-colored badge (design option B): black box = solid accent with light text, white box =
 * accent outline with accent text, circle = solid accent. Never centered on the font's line box
 * (its ascender and descender space leaves text visibly high or low), nor on the strokes (see
 * the drawing code): on the frame each character was designed in.
 */
@Composable
private fun Badge(cls: String, label: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val solid = cls != "box-w"
    val fill = c.primary
    val ink = (if (solid) c.onPrimary else c.primary).toArgb()
    val paint = remember(ink) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textLocale = java.util.Locale.JAPANESE // Japanese glyph forms (Noto Sans CJK JP)
            fontFeatureSettings = "tnum" // tabular digits: every digit the same width, centered in it
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (cls == "num-c") 700 else 600, false)
        }
    }
    val inset = with(LocalDensity.current) { 0.5.dp.toPx() }
    val stroke = with(LocalDensity.current) { 1.2.dp.toPx() }
    Canvas(modifier) {
        val h = size.height - 2 * inset
        val w = size.width - 2 * inset
        val topLeft = Offset(inset, inset)
        if (cls == "num-c") {
            drawCircle(fill, radius = h / 2, center = center)
        } else if (solid) {
            drawRoundRect(fill, topLeft, Size(w, h), CornerRadius(h * 0.2f))
        } else {
            val half = stroke / 2
            drawRoundRect(
                fill, Offset(inset + half, inset + half), Size(w - stroke, h - stroke),
                CornerRadius(h * 0.2f - half), style = Stroke(stroke),
            )
        }
        // Text size relative to the shape: kanji fill ~62% of the box; one digit ~60% of the
        // circle, two digits less so they stay inside it.
        paint.textSize = h * when {
            cls != "num-c" -> 0.62f
            label.length >= 2 -> 0.5f
            else -> 0.6f
        }
        // Vertically: a reference character that fills the height, the same for every badge of
        // a kind (国 for kanji, 0 for digits), so neighbors share one baseline; centering each
        // character's own strokes would make them jump (一 vs 三).
        // Horizontally, kanji: the advance width; the type designer already balanced each kanji
        // in its square (七 leans as written). Digits: their visual center of weight, since
        // this font's digit slots carry more space on the left (measured: 1-3 px right of center
        // in a 46 px circle), and a digit's own outline is lopsided (1's flag).
        val x = if (cls == "num-c") center.x - inkCenterX(label, paint) else center.x - paint.measureText(label) / 2
        val reference = if (cls == "num-c") "0" else "国"
        val frame = android.graphics.Rect().also { paint.getTextBounds(reference, 0, 1, it) }
        val y = center.y - frame.exactCenterY()
        drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
    }
}

/**
 * Horizontal center of weight of [text]'s strokes, measured from the drawing origin: the text is
 * drawn once into a small alpha bitmap and each column is weighted by its ink. Cached per
 * text and size (a handful of labels per page).
 */
private fun inkCenterX(text: String, paint: android.graphics.Paint): Float {
    val key = text + "@" + paint.textSize + "@" + paint.typeface.hashCode()
    inkCenters[key]?.let { return it }
    val bounds = android.graphics.Rect().also { paint.getTextBounds(text, 0, text.length, it) }
    if (bounds.isEmpty) return paint.measureText(text) / 2
    val pad = 2
    val bmp = android.graphics.Bitmap.createBitmap(bounds.width() + 2 * pad, bounds.height() + 2 * pad, android.graphics.Bitmap.Config.ALPHA_8)
    android.graphics.Canvas(bmp).drawText(text, (pad - bounds.left).toFloat(), (pad - bounds.top).toFloat(), paint)
    val row = bmp.rowBytes // may be wider than the bitmap (row alignment)
    val alpha = ByteArray(row * bmp.height).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
    var sum = 0.0
    var weighted = 0.0
    for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
        val a = alpha[y * row + x].toInt() and 0xFF
        sum += a; weighted += a * x.toDouble()
    }
    bmp.recycle()
    val center = if (sum == 0.0) paint.measureText(text) / 2 else (weighted / sum).toFloat() - pad + bounds.left
    inkCenters[key] = center
    return center
}

private val inkCenters = java.util.concurrent.ConcurrentHashMap<String, Float>()
