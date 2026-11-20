package app.jisho.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jisho.core.designsystem.JishoTheme
import app.jisho.core.designsystem.BodyColors
import app.jisho.core.designsystem.badgeContent
import app.jisho.core.designsystem.ja
import app.jisho.core.designsystem.renderBody

/** Debug only (`--es tab badges`): the number badges on real-looking definition text, light and dark. */
@Composable
fun BadgeSamples() {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(false, true).forEach { dark -> JishoTheme(dark = dark) { SampleCard(if (dark) "深色" else "浅色") } }
    }
}

@Composable
private fun SampleCard(title: String) {
    val colors = BodyColors(
        accent = MaterialTheme.colorScheme.primary,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        example = MaterialTheme.colorScheme.secondary,
        link = MaterialTheme.colorScheme.primary,
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(8.dp)) {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val body = renderBody(SAMPLE, colors) {}
                Text(body, style = MaterialTheme.typography.bodyLarge.ja(), inlineContent = badgeContent(body))
            }
        }
    }
}

private const val SAMPLE =
    "<span class=\"box-b\">一</span><span class=\"label\">名</span>二人で互いに槌を打ち合わすこと。" +
        "<br>" + "<span class=\"box-b\">一</span><span class=\"box-b\">二</span><span class=\"box-b\">三</span><span class=\"box-b\">四</span><span class=\"box-b\">五</span><span class=\"box-b\">六</span><span class=\"box-b\">七</span><span class=\"box-b\">八</span><span class=\"box-b\">九</span><span class=\"box-b\">十</span>" +
        "<br>" + "<span class=\"box-b\">十一</span><span class=\"box-b\">十二</span><span class=\"box-b\">十三</span><span class=\"box-b\">十四</span><span class=\"box-b\">十五</span><span class=\"box-b\">十六</span><span class=\"box-b\">十七</span><span class=\"box-b\">十八</span><span class=\"box-b\">十九</span><span class=\"box-b\">二十</span>" +
        "<br>" + "<span class=\"box-w\">一</span>石坂洋次郎の小説。<span class=\"box-w\">二</span>映画。" +
        "<br>" + "<span class=\"box-w\">一</span><span class=\"box-w\">二</span><span class=\"box-w\">三</span><span class=\"box-w\">四</span><span class=\"box-w\">五</span><span class=\"box-w\">六</span><span class=\"box-w\">七</span><span class=\"box-w\">八</span><span class=\"box-w\">九</span><span class=\"box-w\">十</span>" +
        "<br>" + "<span class=\"box-w\">十一</span><span class=\"box-w\">十二</span><span class=\"box-w\">十三</span><span class=\"box-w\">十四</span><span class=\"box-w\">十五</span><span class=\"box-w\">十六</span><span class=\"box-w\">十七</span><span class=\"box-w\">十八</span><span class=\"box-w\">十九</span><span class=\"box-w\">二十</span>" +
        "<br>" + "<span class=\"num-c\">1</span>必要な量を越えて残ったもの。<span class=\"num-c\">2</span>残り。" +
        "<br>" + "<span class=\"num-c\">1</span><span class=\"num-c\">2</span><span class=\"num-c\">3</span><span class=\"num-c\">4</span><span class=\"num-c\">5</span><span class=\"num-c\">6</span><span class=\"num-c\">7</span><span class=\"num-c\">8</span><span class=\"num-c\">9</span><span class=\"num-c\">10</span>" +
        "<br>" + "<span class=\"num-c\">11</span><span class=\"num-c\">12</span><span class=\"num-c\">13</span><span class=\"num-c\">14</span><span class=\"num-c\">15</span><span class=\"num-c\">16</span><span class=\"num-c\">17</span><span class=\"num-c\">18</span><span class=\"num-c\">19</span><span class=\"num-c\">20</span>" +
        "<br>" + "<span class=\"num-c\">21</span><span class=\"num-c\">38</span><span class=\"num-c\">47</span><span class=\"num-c\">56</span><span class=\"num-c\">64</span><span class=\"num-c\">72</span><span class=\"num-c\">88</span><span class=\"num-c\">99</span>"
