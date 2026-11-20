package app.jisho

import android.app.Application
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.SettingsRepository
import app.jisho.core.datapack.DataPackManager
import app.jisho.core.lemmatizer.CombinedLemmatizer
import app.jisho.core.lemmatizer.KuromojiHolder
import app.jisho.core.lemmatizer.Lemmatizer
import app.jisho.core.lemmatizer.LemmatizerMode
import app.jisho.core.lemmatizer.RuleLemmatizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Manual dependency container: the app is small enough not to need a DI framework. */
class JishoApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dataPacks by lazy { DataPackManager(this) }
    val settings by lazy { SettingsRepository(this) }
    val kuromoji = KuromojiHolder()

    @Volatile private var lemmatizerMode = LemmatizerMode.Kuromoji

    /** Changes when search results may differ: the setting changed or Kuromoji finished loading. */
    private val _lemmatizerVersion = MutableStateFlow(0)
    val lemmatizerVersion: StateFlow<Int> = _lemmatizerVersion

    val rules by lazy {
        RuleLemmatizer(RuleLemmatizer.parse(assets.open(RULES_ASSET).bufferedReader().use { it.readText() }))
    }
    val combined by lazy { CombinedLemmatizer({ kuromoji.lemmatizer }, rules) }

    val repository by lazy {
        DictionaryRepository(
            dataPacks,
            lemmatizer = { currentLemmatizer() },
            segmenter = { if (lemmatizerMode == LemmatizerMode.Kuromoji) kuromoji.lemmatizer else null },
        )
    }

    private fun currentLemmatizer(): Lemmatizer =
        if (lemmatizerMode == LemmatizerMode.Kuromoji) combined else rules

    override fun onCreate() {
        super.onCreate()
        // Follow the setting; Kuromoji (about a second to build) loads in the background,
        // and mode A uses the rule table alone until it is ready.
        appScope.launch {
            settings.lemmatizerMode.collect { mode ->
                lemmatizerMode = mode
                _lemmatizerVersion.update { it + 1 }
                if (mode == LemmatizerMode.Kuromoji && kuromoji.lemmatizer == null) {
                    kuromoji.load()
                    _lemmatizerVersion.update { it + 1 }
                }
            }
        }
    }

    companion object {
        const val RULES_ASSET = "deinflect_rules.tsv"
    }
}
