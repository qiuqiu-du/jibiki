package app.jisho.core.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em

/**
 * Inline content for the gaiji pictures of one body ([renderBody] places them). Pictures are
 * black on transparent, so they are tinted with the text color and work in dark mode; each is
 * one line high, with its own width.
 */
fun gaijiContent(images: Map<String, ByteArray>): Map<String, InlineTextContent> =
    images.mapNotNull { (name, bytes) ->
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@mapNotNull null
        val image = bitmap.asImageBitmap()
        val placeholder = Placeholder(
            width = (bitmap.width.toFloat() / bitmap.height).em,
            height = 1.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        )
        GAIJI_PREFIX + name to InlineTextContent(placeholder) {
            Image(image, contentDescription = null, colorFilter = ColorFilter.tint(LocalContentColor.current), modifier = Modifier.fillMaxSize())
        }
    }.toMap()
