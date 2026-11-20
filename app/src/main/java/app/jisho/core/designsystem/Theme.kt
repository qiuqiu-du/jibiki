package app.jisho.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList

// Neutral surfaces with a single indigo accent (Architecture.md §6.2).
private val Light = lightColorScheme(
    primary = Color(0xFF3949AB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF), onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF5B5D72), surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLow = Color(0xFFF5F2FA), surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF), outlineVariant = Color(0xFFC7C5D0),
    background = Color(0xFFFBF8FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF11277C),
    primaryContainer = Color(0xFF2C3E93), onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC4C5DD), surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLow = Color(0xFF1B1B21), surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F), outlineVariant = Color(0xFF46464F),
    background = Color(0xFF131318),
)

/** Japanese text must carry the ja locale so the system picks Japanese glyph forms (Noto Sans CJK JP). */
val JapaneseLocale = LocaleList("ja")

fun TextStyle.ja(): TextStyle = copy(localeList = JapaneseLocale)

@Composable
fun JishoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content,
    )
}
