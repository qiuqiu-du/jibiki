package app.jisho

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.SystemBarStyle
import android.graphics.Color
import app.jisho.core.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import app.jisho.core.designsystem.JishoTheme
import app.jisho.navigation.AppNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packs = (application as JishoApp).dataPacks
        // After an install, an update or a pack change core.db is rebuilt first (about a second).
        val coreCurrent = packs.isCoreCurrent()
        // Debug builds accept `adb shell am start -S -n app.jisho/.MainActivity --es q 食べる`
        // so on-device checks don't depend on the phone's input method.
        val initialQuery = if (BuildConfig.DEBUG) intent.getStringExtra("q").orEmpty() else ""
        val openDictEntry = if (BuildConfig.DEBUG) intent.getStringExtra("open") else null
        val openTab = intent.getStringExtra("tab")?.takeIf { BuildConfig.DEBUG && it in setOf("settings", "favorites") }
        val openBadgeSamples = BuildConfig.DEBUG && intent.getStringExtra("tab") == "badges"
        setContent {
            val theme by (application as JishoApp).settings.theme.collectAsState(ThemeMode.System)
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // The page is not recreated on a light/dark switch (configChanges="uiMode"), so the
            // status and navigation bar icons are switched here.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            JishoTheme(dark = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var ready by remember { mutableStateOf(if (coreCurrent) packs.outdatedDictionaries().isEmpty() else null) }
                    LaunchedEffect(Unit) {
                        if (ready == null) ready = withContext(Dispatchers.IO) { packs.prepareCore(); packs.outdatedDictionaries().isEmpty() }
                    }
                    when (val r = ready) {
                        null -> PreparingScreen()
                        else -> AppNavHost(packsReady = r, initialQuery = initialQuery, openDictEntry = openDictEntry, openTab = openTab, openBadgeSamples = openBadgeSamples)
                    }
                }
            }
        }
    }

    private companion object {
        // The scrims enableEdgeToEdge() uses by default (three-button navigation only).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** Shown while core.db is rebuilt after an install, an update or a pack change. */
@Composable
private fun PreparingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.core_preparing), style = MaterialTheme.typography.bodyLarge)
    }
}
