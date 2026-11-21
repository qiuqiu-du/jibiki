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
import app.jisho.feature.search.HandwritingRecognizer
import app.jisho.feature.search.HandwritingViewModel
import app.jisho.feature.search.InkModelMemory
import app.jisho.feature.settings.SettingsScreen
import app.jisho.feature.settings.BackupViewModel
import app.jisho.feature.settings.SettingsViewModel
import app.jisho.core.database.Inflection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import app.jisho.feature.favorites.FavoritesScreen
import app.jisho.feature.favorites.FavoritesViewModel
import app.jisho.feature.favorites.FolderScreen
import app.jisho.feature.favorites.FolderViewModel
import app.jisho.feature.favorites.UserFiles
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import app.jisho.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.jisho.core.designsystem.pageSwipe
import kotlinx.coroutines.launch

private const val TRANSITION_MS = 200

private object Routes {
    const val IMPORT = "import"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val FOLDER = "folder/{id}"
    fun folder(id: Long) = "folder/$id"
    const val ENTRY = "entry/{id}?from={from}&how={how}"
    const val DICT = "dict/{dictId}/{id}"
    const val LOOKUP = "lookup/{key}"
    /** [from]/[how]: the inflected form typed and its labels, shown on the entry page. */
    fun entry(id: Long, from: String = "", how: List<String> = emptyList()) =
        "entry/$id?from=${Uri.encode(from)}&how=${Uri.encode(how.joinToString(","))}"
    fun dict(dictId: String, id: Long) = "dict/$dictId/$id"
    fun lookup(key: String) = "lookup/${Uri.encode(key)}"
}

private fun entryRoute(c: app.jisho.core.database.Candidate): String =
    c.inflection?.let { Routes.entry(c.entryId, it.from, it.reasons) } ?: Routes.entry(c.entryId)

/** Where a saved word ([app.jisho.core.userdata.WordRef.target]) opens. */
private fun targetRoute(target: String): String? {
    val p = target.split(':', limit = 3)
    return when (p[0]) {
        "core" -> p.getOrNull(1)?.toLongOrNull()?.let { Routes.entry(it) }
        "dict" -> p.getOrNull(2)?.toLongOrNull()?.let { Routes.dict(p[1], it) }
        "lookup" -> p.getOrNull(1)?.let { Routes.lookup(target.removePrefix("lookup:")) }
        else -> null
    }
}

private val CreationExtras.app: JishoApp get() = this[APPLICATION_KEY] as JishoApp

@Composable
private inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: CreationExtras.() -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer(create) })

@Composable
fun AppNavHost(
    packsReady: Boolean,
    initialQuery: String = "",
    openDictEntry: String? = null,
    /** Debug: "settings" or "favorites" opens that tab (the search field's keyboard covers the bottom bar). */
    openTab: String? = null,
    openBadgeSamples: Boolean = false,
) {
    if (openBadgeSamples) { app.jisho.feature.settings.BadgeSamples(); return }
    val nav = rememberNavController()
    // Debug: "meikyo3:51920" opens that dictionary entry on top of the search screen.
    androidx.compose.runtime.LaunchedEffect(openDictEntry) {
        val (d, id) = openDictEntry?.split(':')?.takeIf { it.size == 2 } ?: return@LaunchedEffect
        id.toLongOrNull()?.let { if (packsReady) nav.navigate(Routes.dict(d, it)) }
    }
    NavHost(
        nav,
        startDestination = if (packsReady) Routes.SEARCH else Routes.IMPORT,
        // Short transitions: the default 700 ms crossfade kept the old page over the new one
        // and made taps right after going back feel ignored.
        enterTransition = { fadeIn(tween(TRANSITION_MS)) + slideInHorizontally(tween(TRANSITION_MS)) { it / 12 } },
        exitTransition = { fadeOut(tween(TRANSITION_MS / 2)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { fadeOut(tween(TRANSITION_MS)) + slideOutHorizontally(tween(TRANSITION_MS)) { it / 12 } },
    ) {
        composable(Routes.IMPORT) {
            ImportScreen(appViewModel { ImportViewModel(app.dataPacks, app.repository) }) {
                nav.navigate(Routes.SEARCH) { popUpTo(Routes.IMPORT) { inclusive = true } }
            }
        }
        composable(Routes.SEARCH) {
            // Search, favorites and settings side by side: swipe or tap the bottom bar.
            val startPage = when (openTab) { "favorites" -> 1; "settings" -> 2; else -> 0 }
            Home(startPage) { page, active ->
                when (page) {
                    0 -> SearchScreen(
                        viewModel = appViewModel { SearchViewModel(app.repository, initialQuery, refresh = app.resultsVersion, user = app.userStore) },
                        handwriting = appViewModel {
                            val settings = app.settings
                            HandwritingViewModel(HandwritingRecognizer(), object : InkModelMemory {
                                override suspend fun wasReady() = settings.handwritingModelReady()
                                override suspend fun remember(ready: Boolean) = settings.setHandwritingModelReady(ready)
                            })
                        },
                        onOpenEntry = { c -> nav.navigate(entryRoute(c)) },
                        onOpenDictEntry = { d, id -> nav.navigate(Routes.dict(d, id)) },
                        onLookup = { nav.navigate(Routes.lookup(it)) },
                        onOpenTarget = { t -> targetRoute(t)?.let { nav.navigate(it) } },
                        active = active,
                    )
                    1 -> FavoritesScreen(appViewModel { FavoritesViewModel(app.userStore, app.transfer, UserFiles(app)) }) { id ->
                        nav.navigate(Routes.folder(id))
                    }
                    else -> SettingsScreen(
                        appViewModel { SettingsViewModel(app.settings, app.repository) },
                        appViewModel { BackupViewModel(app.backups) },
                    ) { nav.navigate(Routes.IMPORT) }
                }
            }
        }
        composable(Routes.FOLDER, listOf(navArgument("id") { type = NavType.LongType })) {
            val id = it.arguments!!.getLong("id")
            FolderScreen(
                appViewModel("folder$id") { FolderViewModel(id, app.userStore, app.repository) },
                folderId = id,
                onBack = { nav.popBackStack() },
                onOpenTarget = { t -> targetRoute(t)?.let { r -> nav.navigate(r) } },
            )
        }
        val entryCallbacks: @Composable (EntryTarget, String) -> Unit = { target, key ->
            EntryScreen(
                viewModel = appViewModel(key) { EntryViewModel(app.repository, target, app.userStore) },
                onBack = { nav.popBackStack() },
                onOpenDictEntry = { d, id -> nav.navigate(Routes.dict(d, id)) },
                onLookup = { nav.navigate(Routes.lookup(it)) },
                onOpenEntry = { c -> nav.navigate(entryRoute(c)) },
            )
        }
        composable(
            Routes.ENTRY,
            listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("from") { type = NavType.StringType; defaultValue = "" },
                navArgument("how") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            val args = it.arguments!!
            val id = args.getLong("id")
            val from = args.getString("from").orEmpty()
            val how = args.getString("how").orEmpty().split(',').filter { s -> s.isNotEmpty() }
            entryCallbacks(EntryTarget.Core(id, from.takeIf { f -> f.isNotEmpty() }?.let { f -> Inflection(f, how) }), "core$id$from")
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

/**
 * The three main pages side by side (search, favorites, settings): switched by the bottom bar
 * or by swiping, with the same direction rule as the entry page's tabs ([pageSwipe]). All three
 * stay composed, so each keeps its state (typed text, scroll position) while away.
 * [content]: (page, active) -> the page; active is true for the page in view.
 */
@Composable
private fun Home(startPage: Int, content: @Composable (Int, Boolean) -> Unit) {
    val pager = rememberPagerState(initialPage = startPage) { HOME_PAGES.size }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Leaving the search page: put the keyboard away.
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != 0) { keyboard?.hide(); focus.clearFocus() }
    }
    // Back on favorites or settings returns to search, as before.
    BackHandler(pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
    Scaffold(
        bottomBar = {
            BottomBar(HOME_PAGES, selected = pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).pageSwipe(pager, enabled = true)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = false, beyondViewportPageCount = HOME_PAGES.size) { page ->
                content(page, page == pager.settledPage)
            }
        }
    }
}

private val HOME_PAGES = listOf(
    Icons.Default.Search to R.string.nav_search,
    Icons.Default.Star to R.string.nav_favorites,
    Icons.Default.Settings to R.string.nav_settings,
)

/** Slim bar: hairline on top, selection shown by color only (no pill indicator). */
@Composable
private fun BottomBar(items: List<Pair<ImageVector, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                items.forEachIndexed { i, (icon, label) ->
                    val color by animateColorAsState(
                        if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tab",
                    )
                    Column(
                        Modifier.weight(1f).fillMaxHeight().selectable(selected = i == selected, role = Role.Tab) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
