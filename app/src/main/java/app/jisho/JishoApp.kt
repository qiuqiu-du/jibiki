package app.jisho

import android.app.Application
import android.app.UiModeManager
import android.os.Build
import app.jisho.core.data.ThemeMode
import app.jisho.core.data.DictPrefs
import app.jisho.core.data.SummaryPrefs
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.data.SettingsRepository
import app.jisho.core.datapack.DataPackManager
import app.jisho.core.database.AndroidWritableDb
import app.jisho.core.userdata.BackupManager
import app.jisho.core.userdata.FavoritesTransfer
import app.jisho.core.userdata.UserStore
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
import kotlinx.coroutines.withContext

/** Manual dependency container: the app is small enough not to need a DI framework. */
class JishoApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dataPacks by lazy { DataPackManager(this) }
    val settings by lazy { SettingsRepository(this) }
    val kuromoji = KuromojiHolder()

    @Volatile private var lemmatizerMode = LemmatizerMode.Kuromoji
    @Volatile private var dictPrefs = DictPrefs()
    @Volatile private var summaryPrefs = SummaryPrefs()

    /** Changes when search results may differ: a setting changed or Kuromoji finished loading. */
    private val _resultsVersion = MutableStateFlow(0)
    val resultsVersion: StateFlow<Int> = _resultsVersion

    val rules by lazy {
        RuleLemmatizer(RuleLemmatizer.parse(assets.open(RULES_ASSET).bufferedReader().use { it.readText() }))
    }
    val combined by lazy { CombinedLemmatizer({ kuromoji.lemmatizer }, rules) }

    val repository by lazy {
        DictionaryRepository(
            dataPacks,
            lemmatizer = { currentLemmatizer() },
            segmenter = { if (lemmatizerMode == LemmatizerMode.Kuromoji) kuromoji.lemmatizer else null },
            prefs = { dictPrefs },
            summaries = { summaryPrefs },
        )
    }

    /** The user's favorites and history (user.db, the only file a backup needs). */
    val userStore by lazy {
        UserStore(AndroidWritableDb(getDatabasePath(USER_DB).also { it.parentFile?.mkdirs() }), getString(R.string.favorites_unnamed))
    }
    val backups by lazy { BackupManager(this, userStore, settings) }
    val transfer by lazy { FavoritesTransfer(userStore, repository) }

    private fun currentLemmatizer(): Lemmatizer =
        if (lemmatizerMode == LemmatizerMode.Kuromoji) combined else rules

    override fun onCreate() {
        super.onCreate()
        // Follow the setting; Kuromoji (about a second to build) loads in the background,
        // and mode A uses the rule table alone until it is ready.
        appScope.launch {
            settings.dictPrefs.collect { p ->
                dictPrefs = p
                // A dictionary turned off is not consulted for accents or ranking either: core.db
                // is rebuilt without it (about a second; searches meanwhile use the old one).
                if (dataPacks.setDisabled(p.disabled) && dataPacks.coreFile.exists()) {
                    withContext(Dispatchers.IO) { dataPacks.prepareCore() }
                    repository.reopen()
                }
                _resultsVersion.update { v -> v + 1 }
            }
        }
        // Android 12+ keeps a per-app light/dark choice and applies it before the first frame
        // (no flash, and web pages and system bars follow it); older versions only get the
        // Compose colors (MainActivity).
        if (Build.VERSION.SDK_INT >= 31) appScope.launch {
            val uiMode = getSystemService(UiModeManager::class.java)
            settings.theme.collect { t ->
                uiMode.setApplicationNightMode(
                    when (t) {
                        ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                    },
                )
            }
        }
        appScope.launch {
            settings.summaryPrefs.collect { summaryPrefs = it; _resultsVersion.update { v -> v + 1 } }
        }
        appScope.launch {
            settings.lemmatizerMode.collect { mode ->
                lemmatizerMode = mode
                _resultsVersion.update { it + 1 }
                if (mode == LemmatizerMode.Kuromoji && kuromoji.lemmatizer == null) {
                    kuromoji.load()
                    _resultsVersion.update { it + 1 }
                }
            }
        }
    }

    companion object {
        const val RULES_ASSET = "deinflect_rules.tsv"
        const val USER_DB = "user.db"
    }
}
