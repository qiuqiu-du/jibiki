package app.jisho

import android.app.Application
import app.jisho.core.data.DictionaryRepository
import app.jisho.core.datapack.DataPackManager

/** Manual dependency container: the app is small enough not to need a DI framework. */
class JishoApp : Application() {
    val dataPacks by lazy { DataPackManager(this) }
    val repository by lazy { DictionaryRepository(dataPacks) }
}
