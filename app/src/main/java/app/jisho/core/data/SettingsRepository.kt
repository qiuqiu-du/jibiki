package app.jisho.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.jisho.core.lemmatizer.LemmatizerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings")

/** User settings (DataStore): lemmatizer, dictionary order and on/off, plus remembered app state. */
class SettingsRepository(private val context: Context) {
    private val lemmatizerKey = stringPreferencesKey("lemmatizer")
    private val inkModelReadyKey = booleanPreferencesKey("handwriting_model_ready")
    private val dictOrderKey = stringPreferencesKey("dict_order")
    private val dictDisabledKey = stringPreferencesKey("dict_disabled")

    val lemmatizerMode: Flow<LemmatizerMode> = context.settingsStore.data.map { prefs ->
        prefs[lemmatizerKey]?.let { name -> LemmatizerMode.entries.firstOrNull { it.name == name } } ?: LemmatizerMode.Kuromoji
    }

    suspend fun setLemmatizerMode(mode: LemmatizerMode) {
        context.settingsStore.edit { it[lemmatizerKey] = mode.name }
    }

    /** Dictionary order and on/off state (stored as comma-separated ids). */
    val dictPrefs: Flow<DictPrefs> = context.settingsStore.data.map { prefs ->
        DictPrefs(
            order = prefs[dictOrderKey].orEmpty().split(',').filter { it.isNotEmpty() },
            disabled = prefs[dictDisabledKey].orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
        )
    }

    suspend fun setDictPrefs(p: DictPrefs) {
        context.settingsStore.edit {
            it[dictOrderKey] = p.order.joinToString(",")
            it[dictDisabledKey] = p.disabled.sorted().joinToString(",")
        }
    }

    /** Settings a backup carries (not device state such as the handwriting model). */
    suspend fun backupSettings(): Map<String, String> {
        val prefs = context.settingsStore.data.first()
        return listOf(lemmatizerKey, dictOrderKey, dictDisabledKey).mapNotNull { k -> prefs[k]?.let { k.name to it } }.toMap()
    }

    /** Restores what [backupSettings] saved; keys it doesn't know are ignored, missing ones reset. */
    suspend fun restoreSettings(values: Map<String, String>) {
        context.settingsStore.edit { prefs ->
            listOf(lemmatizerKey, dictOrderKey, dictDisabledKey).forEach { k ->
                val v = values[k.name]
                if (v != null) prefs[k] = v else prefs.remove(k)
            }
        }
    }

    /** Whether the handwriting model was found last time (lets the panel skip ML Kit's slow first check). */
    suspend fun handwritingModelReady(): Boolean = context.settingsStore.data.first()[inkModelReadyKey] ?: false

    suspend fun setHandwritingModelReady(ready: Boolean) {
        context.settingsStore.edit { it[inkModelReadyKey] = ready }
    }
}
