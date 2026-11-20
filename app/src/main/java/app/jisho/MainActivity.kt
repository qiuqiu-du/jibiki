package app.jisho

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import app.jisho.core.designsystem.JishoTheme
import app.jisho.navigation.AppNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ready = (application as JishoApp).dataPacks.isReady()
        // Debug builds accept `adb shell am start -S -n app.jisho/.MainActivity --es q 食べる`
        // so on-device checks don't depend on the phone's input method.
        val initialQuery = if (BuildConfig.DEBUG) intent.getStringExtra("q").orEmpty() else ""
        val openDictEntry = if (BuildConfig.DEBUG) intent.getStringExtra("open") else null
        val openSettings = BuildConfig.DEBUG && intent.getStringExtra("tab") == "settings"
        val openBadgeSamples = BuildConfig.DEBUG && intent.getStringExtra("tab") == "badges"
        if (BuildConfig.DEBUG) {
            DebugOptions.labelStyle = intent.getStringExtra("labelStyle")
                ?.let { s -> app.jisho.core.designsystem.LabelStyle.entries.firstOrNull { it.name == s } }
        }
        setContent {
            JishoTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost(packsReady = ready, initialQuery = initialQuery, openDictEntry = openDictEntry, openSettings = openSettings, openBadgeSamples = openBadgeSamples)
                }
            }
        }
    }
}
