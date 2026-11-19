package app.jisho.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.jisho.JishoApp
import app.jisho.feature.entry.EntryScreen
import app.jisho.feature.entry.EntryTarget
import app.jisho.feature.entry.EntryViewModel
import app.jisho.feature.importpack.ImportScreen
import app.jisho.feature.importpack.ImportViewModel
import app.jisho.feature.search.SearchScreen
import app.jisho.feature.search.SearchViewModel

private object Routes {
    const val IMPORT = "import"
    const val SEARCH = "search"
    const val ENTRY = "entry/{id}"
    const val DICT = "dict/{dictId}/{id}"
    const val LOOKUP = "lookup/{key}"
    fun entry(id: Long) = "entry/$id"
    fun dict(dictId: String, id: Long) = "dict/$dictId/$id"
    fun lookup(key: String) = "lookup/${Uri.encode(key)}"
}

private val CreationExtras.app: JishoApp get() = this[APPLICATION_KEY] as JishoApp

@Composable
private inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: CreationExtras.() -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer(create) })

@Composable
fun AppNavHost(packsReady: Boolean, initialQuery: String = "", openDictEntry: String? = null) {
    val nav = rememberNavController()
    // Debug: "meikyo3:51920" opens that dictionary entry on top of the search screen.
    androidx.compose.runtime.LaunchedEffect(openDictEntry) {
        val (d, id) = openDictEntry?.split(':')?.takeIf { it.size == 2 } ?: return@LaunchedEffect
        id.toLongOrNull()?.let { if (packsReady) nav.navigate(Routes.dict(d, it)) }
    }
    NavHost(nav, startDestination = if (packsReady) Routes.SEARCH else Routes.IMPORT) {
        composable(Routes.IMPORT) {
            ImportScreen(appViewModel { ImportViewModel(app.dataPacks, app.repository) }) {
                nav.navigate(Routes.SEARCH) { popUpTo(Routes.IMPORT) { inclusive = true } }
            }
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                viewModel = appViewModel { SearchViewModel(app.repository, initialQuery) },
                onOpenEntry = { nav.navigate(Routes.entry(it)) },
                onOpenDictEntry = { d, id -> nav.navigate(Routes.dict(d, id)) },
            )
        }
        val entryCallbacks: @Composable (EntryTarget, String) -> Unit = { target, key ->
            EntryScreen(
                viewModel = appViewModel(key) { EntryViewModel(app.repository, target) },
                onBack = { nav.popBackStack() },
                onOpenDictEntry = { d, id -> nav.navigate(Routes.dict(d, id)) },
                onLookup = { nav.navigate(Routes.lookup(it)) },
            )
        }
        composable(Routes.ENTRY, listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments!!.getLong("id")
            entryCallbacks(EntryTarget.Core(id), "core$id")
        }
        composable(
            Routes.DICT,
            listOf(navArgument("dictId") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
        ) {
            val d = it.arguments!!.getString("dictId")!!
            val id = it.arguments!!.getLong("id")
            entryCallbacks(EntryTarget.Dict(d, id), "dict$d$id")
        }
        composable(Routes.LOOKUP, listOf(navArgument("key") { type = NavType.StringType })) {
            val key = it.arguments!!.getString("key")!!
            entryCallbacks(EntryTarget.Lookup(key), "lookup$key")
        }
    }
}
