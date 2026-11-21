package app.jisho.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/**
 * The interface language (design-spec 5). Android 13+ keeps a per-app language itself, so it is
 * not in the settings store or backups; older versions follow the system ([supported] false).
 * Tags: "zh-CN", "ja", "en"; null follows the system.
 */
interface AppLanguage {
    val supported: Boolean
    fun current(): String?
    fun set(tag: String?)
}

class AndroidAppLanguage(private val context: Context) : AppLanguage {
    override val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    override fun current(): String? {
        if (!supported) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else TAGS.firstOrNull { it.startsWith(locales[0].language) }
    }

    override fun set(tag: String?) {
        if (!supported) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    companion object {
        /** Same as res/xml/locales_config.xml. */
        val TAGS = listOf("zh-CN", "ja", "en")
    }
}
