package app.jisho

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.jisho.core.designsystem.ReorderState
import app.jisho.core.designsystem.longPressDrag
import app.jisho.core.designsystem.rememberReorderState
import app.jisho.core.designsystem.reorderable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.jisho.core.data.EntryExpand
import app.jisho.core.data.SettingsRepository
import app.jisho.core.data.ThemeMode
import app.jisho.core.designsystem.JishoTheme
import app.jisho.core.userdata.BackupManager
import app.jisho.feature.entry.EntryScreen
import app.jisho.feature.entry.EntryTarget
import app.jisho.feature.entry.EntryViewModel
import app.jisho.feature.search.HandwritingRecognizer
import app.jisho.feature.search.HandwritingViewModel
import app.jisho.feature.search.InkModelMemory
import app.jisho.feature.search.SearchScreen
import app.jisho.feature.search.SearchViewModel
import app.jisho.feature.settings.AppInfo
import app.jisho.feature.settings.BackupViewModel
import app.jisho.feature.settings.PackInfo
import app.jisho.feature.settings.SettingsScreen
import app.jisho.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * All Compose UI tests: gestures driven by real touch events in a test activity, checked by
 * state, not screenshots; nothing of the user's data. Add new UI tests here, one section each.
 */
@RunWith(AndroidJUnit4::class)
class DeviceUiTest {
    @get:Rule val rule = createComposeRule()

    // --- Drag to reorder (Reorder.kt): each row is 64 dp in a 400 dp list. ---

    private val order = mutableStateListOf<Int>().apply { addAll(0 until 30) }
    private lateinit var list: LazyListState
    private lateinit var reorder: ReorderState

    private fun show(firstVisible: Int = 0) {
        rule.setContent {
            list = rememberLazyListState(firstVisible)
            reorder = rememberReorderState(list) { from, to ->
                order.add(order.indexOf(to), order.removeAt(order.indexOf(from)))
            }
            LazyColumn(Modifier.size(300.dp, 400.dp), state = list) {
                items(order, key = { it }) { n ->
                    Box(Modifier.reorderable(reorder, n, Modifier.animateItem()).fillMaxWidth().height(64.dp).longPressDrag(reorder, n).testTag("row$n")) {
                        Text("$n")
                    }
                }
            }
        }
    }

    /** Holds [row] (long press), moves it by [dy] px in steps, waits [holdMs] there, then lifts. */
    private fun drag(row: Int, dy: Float, holdMs: Long = 0) {
        rule.onNodeWithTag("row$row").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            val steps = 20
            repeat(steps) { moveBy(androidx.compose.ui.geometry.Offset(0f, dy / steps), delayMillis = 16) }
            var t = 0L
            while (t < holdMs) { moveBy(androidx.compose.ui.geometry.Offset.Zero, delayMillis = 16); t += 16 }
            up()
        }
        rule.waitForIdle()
    }

    @Test fun holdingTheTopOrBottomRowWithoutMovingDoesNotScroll() {
        show(firstVisible = 10)
        rule.waitForIdle()
        val top = list.firstVisibleItemIndex
        drag(order[top], 0f, holdMs = 800)
        assertEquals(top, list.firstVisibleItemIndex)
        val bottom = list.layoutInfo.visibleItemsInfo.last { it.offset + it.size <= list.layoutInfo.viewportEndOffset }.key as Int
        drag(bottom, 0f, holdMs = 800)
        assertEquals(top, list.firstVisibleItemIndex)
        assertEquals((0 until 30).toList(), order.toList())
    }

    @Test fun aRowCanBeDraggedAboveTheFirstRow() {
        show()
        val px = with(rule.density) { 64.dp.toPx() }
        drag(3, -3.5f * px)
        assertEquals(listOf(3, 0, 1, 2, 4), order.take(5))
        assertEquals(0, list.firstVisibleItemIndex)
    }

    @Test fun theFirstRowCanBeDraggedDownAndBack() {
        show()
        val px = with(rule.density) { 64.dp.toPx() }
        drag(0, 2f * px)
        assertEquals(listOf(1, 2, 0, 3), order.take(4))
        drag(0, -2f * px)
        assertEquals(listOf(0, 1, 2, 3), order.take(4))
    }

    @Test fun draggingToTheTopEdgeScrollsUpToTheStart() {
        show(firstVisible = 10)
        rule.waitForIdle()
        val px = with(rule.density) { 64.dp.toPx() }
        val row = order[12] // third visible row
        drag(row, -2.2f * px, holdMs = 3000)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(row, order.first())
    }

    // --- Settings (design-spec 5): choices are stored, labels fit, and the app follows them. ---
    // Settings go to a scratch DataStore; packs are only read; no backup or model is touched.

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JishoApp
    private var stores = 0

    private fun scratchSettings(): SettingsRepository {
        val file = File(app.cacheDir, "ui-test-${System.nanoTime()}-${stores++}.preferences_pb").also { it.delete() }
        return SettingsRepository(app, PreferenceDataStoreFactory.create(produceFile = { file }))
    }

    private fun handwriting() = HandwritingViewModel(HandwritingRecognizer(), object : InkModelMemory {
        override suspend fun wasReady() = false
        override suspend fun remember(ready: Boolean) {}
    })

    /** The settings page as the app shows it, on [settings]; the model counts as downloaded. */
    private fun showSettings(settings: SettingsRepository) {
        val info = AppInfo("test", listOf(PackInfo(null, "2026-11-21", 32_000_000), PackInfo("デジタル大辞泉", "2026-11-21", 46_000_000)), 25_000_000, 500_000)
        val viewModel = SettingsViewModel(settings, app.repository) { info }
        val backup = BackupViewModel(BackupManager(app, app.userStore, settings))
        rule.setContent { JishoTheme { SettingsScreen(viewModel, backup, handwriting(), active = true) {} } }
        rule.waitForIdle()
    }

    @Test fun settingsChoicesAreStored() = runBlocking {
        val settings = scratchSettings()
        showSettings(settings)
        rule.onNodeWithText(text(R.string.settings_entry_expand_none)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.settings_theme_dark)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.settings_auto_keyboard)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(EntryExpand.None, settings.entryExpand.first())
        assertEquals(ThemeMode.Dark, settings.theme.first())
        assertEquals(false, settings.autoKeyboard.first())
    }

    /** Every button's label is on one line and not cut off (no "…", no wrapping). */
    @Test fun settingsButtonsShowTheirWholeLabel() {
        showSettings(scratchSettings())
        val labels = rule.onAllNodes(
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("no buttons found", labels.size >= 4) // reimport, backup, restore, delete
        for (node in labels) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            val label = layout.layoutInput.text.text
            assertEquals("$label wraps", 1, layout.lineCount)
            // Compared directly: hasVisualOverflow also fires when the width is rounded down by
            // less than a pixel (290 px laid out for a 288.8 px line).
            assertFalse("$label is cut off", layout.isLineEllipsized(0) || layout.getLineRight(0) > layout.size.width + 1)
            // A trailing "…" reads as cut-off text on a short button, so labels never end with one.
            assertFalse("$label ends with …", label.endsWith("…") || label.endsWith("..."))
        }
    }

    /** Entry page: the default-expand setting decides which dictionaries show their entries. */
    @Test fun entryPageExpandsDictionariesAsSet() = runBlocking {
        assumeTrue("import the data packs in the app first", app.dataPacks.isReady())
        val id = app.repository.search("光").words.first { it.exact }.entryId
        val sections = app.repository.entry(id)!!.definitions.filter { it.second.isNotEmpty() }
        assumeTrue("needs a word in several dictionaries", sections.size >= 2)
        val titles = sections.flatMap { s -> s.second.map { it.title } }.distinct()
        fun shownEntries() = titles.sumOf { rule.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().size }
        val expected = mapOf(
            EntryExpand.First to sections.first().second.size,
            EntryExpand.All to sections.sumOf { it.second.size },
            EntryExpand.None to 0,
        )
        var expand by mutableStateOf(EntryExpand.First)
        rule.setContent {
            JishoTheme {
                key(expand) {
                    EntryScreen(EntryViewModel(app.repository, EntryTarget.Core(id), user = null, expandSetting = flowOf(expand)), {}, { _, _ -> }, {}, {})
                }
            }
        }
        for ((setting, count) in expected) {
            expand = setting
            rule.waitUntil(5_000) { shownEntries() == count }
            assertEquals(setting.name, count, shownEntries())
        }
    }

    /** Search page: the search field takes focus (and the keyboard) on launch only when the setting is on. */
    @Test fun searchFieldFocusFollowsTheKeyboardSetting() {
        var auto by mutableStateOf(true)
        rule.setContent {
            JishoTheme {
                key(auto) {
                    SearchScreen(
                        SearchViewModel(app.repository, debounceMs = 0, autoKeyboardSetting = flowOf(auto)),
                        handwriting(), onOpenEntry = {}, onOpenDictEntry = { _, _ -> }, onLookup = {},
                    )
                }
            }
        }
        rule.waitUntil(5_000) { runCatching { rule.onNode(hasSetTextAction()).assertIsFocused() }.isSuccess }
        auto = false
        rule.waitForIdle()
        Thread.sleep(500) // the setting is read asynchronously; give a wrong focus time to happen
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    private fun text(res: Int) = app.getString(res)
}
