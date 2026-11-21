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
import androidx.activity.SystemBarStyle
import android.graphics.Color
import app.jisho.core.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import app.jisho.core.designsystem.JishoTheme
import app.jisho.navigation.AppNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = (application as JishoApp).dataPacks.isReady()
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
                    AppNavHost(packsReady = ready, initialQuery = initialQuery, openDictEntry = openDictEntry, openTab = openTab, openBadgeSamples = openBadgeSamples)
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
