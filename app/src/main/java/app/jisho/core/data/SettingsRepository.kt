package app.jisho.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.jisho.core.lemmatizer.LemmatizerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** Which dictionaries an entry page opens expanded (design-spec 5). */
enum class EntryExpand { First, All, None }

/** Light or dark colors: following the system, or fixed by the user. */
enum class ThemeMode { System, Light, Dark }

/** User settings (DataStore): lemmatizer, dictionary order and on/off, plus remembered app state. */
class SettingsRepository(
    context: Context,
    /** The app's settings file; tests pass a scratch store so they never touch the user's settings. */
    private val store: DataStore<Preferences> = context.settingsStore,
) {
    private val lemmatizerKey = stringPreferencesKey("lemmatizer")
    private val inkModelReadyKey = booleanPreferencesKey("handwriting_model_ready")
    private val dictOrderKey = stringPreferencesKey("dict_order")
    private val dictDisabledKey = stringPreferencesKey("dict_disabled")
    private val summaryKey = stringPreferencesKey("summary_lines")
    private val entryExpandKey = stringPreferencesKey("entry_expand")
    private val themeKey = stringPreferencesKey("theme")
    private val autoKeyboardKey = booleanPreferencesKey("auto_keyboard")

    /** Settings a backup carries (not device state such as the handwriting model). */
    private val backedUp = listOf(lemmatizerKey.name, dictOrderKey.name, dictDisabledKey.name, summaryKey.name, entryExpandKey.name, themeKey.name, autoKeyboardKey.name)

    val lemmatizerMode: Flow<LemmatizerMode> = store.data.map { prefs ->
        prefs[lemmatizerKey]?.let { name -> LemmatizerMode.entries.firstOrNull { it.name == name } } ?: LemmatizerMode.Kuromoji
    }

    suspend fun setLemmatizerMode(mode: LemmatizerMode) {
        store.edit { it[lemmatizerKey] = mode.name }
    }

    /** Dictionary order and on/off state (stored as comma-separated ids). */
    val dictPrefs: Flow<DictPrefs> = store.data.map { prefs ->
        DictPrefs(
            order = prefs[dictOrderKey].orEmpty().split(',').filter { it.isNotEmpty() },
            disabled = prefs[dictDisabledKey].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
        )
    }

    suspend fun setDictPrefs(p: DictPrefs) {
        store.edit {
            it[dictOrderKey] = p.order.joinToString(",")
            it[dictDisabledKey] = p.disabled.sorted().joinToString(",")
        }
    }

    /** Where one-line meanings appear (see [SummaryPrefs]). */
    val summaryPrefs: Flow<SummaryPrefs> = store.data.map { SummaryPrefs.decode(it[summaryKey]) }

    suspend fun setSummaryPrefs(p: SummaryPrefs) {
        store.edit { it[summaryKey] = p.encode() }
    }

    val entryExpand: Flow<EntryExpand> = store.data.map { prefs ->
        EntryExpand.entries.firstOrNull { it.name == prefs[entryExpandKey] } ?: EntryExpand.First
    }

    suspend fun setEntryExpand(e: EntryExpand) {
        store.edit { it[entryExpandKey] = e.name }
    }

    val theme: Flow<ThemeMode> = store.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[themeKey] } ?: ThemeMode.System
    }

    suspend fun setTheme(t: ThemeMode) {
        store.edit { it[themeKey] = t.name }
    }

    /** Raise the keyboard on launch and on returning from an entry page (default on). */
    val autoKeyboard: Flow<Boolean> = store.data.map { it[autoKeyboardKey] ?: true }

    suspend fun setAutoKeyboard(on: Boolean) {
        store.edit { it[autoKeyboardKey] = on }
    }

    /** Settings a backup carries, as text (not device state such as the handwriting model). */
    suspend fun backupSettings(): Map<String, String> {
        val prefs = store.data.first().asMap()
        return prefs.filterKeys { it.name in backedUp }.map { (k, v) -> k.name to v.toString() }.toMap()
    }

    /** Restores what [backupSettings] saved; keys it doesn't know are ignored, missing ones reset. */
    suspend fun restoreSettings(values: Map<String, String>) {
        store.edit { prefs ->
            listOf(lemmatizerKey, dictOrderKey, dictDisabledKey, summaryKey, entryExpandKey, themeKey).forEach { k ->
                val v = values[k.name]
                if (v != null) prefs[k] = v else prefs.remove(k)
            }
            val auto = values[autoKeyboardKey.name]?.toBooleanStrictOrNull()
            if (auto != null) prefs[autoKeyboardKey] = auto else prefs.remove(autoKeyboardKey)
        }
    }

    /** Whether the handwriting model was found last time (lets the panel skip ML Kit's slow first check). */
    suspend fun handwritingModelReady(): Boolean = store.data.first()[inkModelReadyKey] ?: false

    suspend fun setHandwritingModelReady(ready: Boolean) {
        store.edit { it[inkModelReadyKey] = ready }
    }
}
