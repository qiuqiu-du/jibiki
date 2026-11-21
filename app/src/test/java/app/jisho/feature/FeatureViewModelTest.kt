package app.jisho.feature

import app.jisho.core.data.DictionaryRepository
import app.jisho.feature.entry.EntryTarget
import app.jisho.feature.entry.EntryUiState
import app.jisho.feature.entry.EntryPage
import app.jisho.core.userdata.WordRef
import app.jisho.feature.entry.EntryViewModel
import app.jisho.feature.search.SearchViewModel
import app.jisho.testing.TestPacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for feature ViewModels: state produced for the search and entry screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureViewModelTest {
    private val repo by lazy { DictionaryRepository(TestPacks.opener()) }

    @Before fun setUp() {
        TestPacks.assumePresent()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun page(target: EntryTarget): EntryPage = (entryState(target) as EntryUiState.Ready).page

    private fun entryState(target: EntryTarget): EntryUiState = runBlocking {
        val vm = EntryViewModel(repo, target)
        withTimeout(10_000) { vm.state.first { it != EntryUiState.Loading } }
    }

    @Test fun searchEmitsResultsForTheQuery() = runBlocking {
        val vm = SearchViewModel(repo, debounceMs = 0)
        val collector = launch(Dispatchers.Main) { vm.result.collect {} } // result is WhileSubscribed
        vm.onQueryChange("あめ")
        val r = withTimeout(10_000) { vm.result.first { it?.query == "あめ" } }!!
        assertEquals("雨", r.words.first().headword)
        vm.onQueryChange("")
        assertEquals(null, withTimeout(10_000) { vm.result.first { it == null } })
        collector.cancel()
    }

    /** Keyboard: open on launch and after coming back from a word; not after settings. */
    @Test fun keyboardOpensOnLaunchAndAfterReturningFromAWord() {
        val vm = SearchViewModel(repo, debounceMs = 0)
        assertTrue(vm.consumeFocusRequest())       // app launch
        assertFalse(vm.consumeFocusRequest())      // back from settings / app switcher
        vm.focusOnReturn()                         // opened an entry
        assertTrue(vm.consumeFocusRequest())       // back from the entry
        assertFalse(vm.consumeFocusRequest())
    }

    @Test fun coreEntryShowsOnlyDictionariesWithDefinitions() {
        val s = page(EntryTarget.Core(1358280))
        assertEquals("食べる", s.head!!.headword)
        assertTrue(s.sections.isNotEmpty() && s.sections.all { it.second.isNotEmpty() })
        assertEquals(WordRef("食べる", "たべる", "core:1358280"), s.word)
    }

    @Test fun lookupLinkPrefersAnExactWord() {
        assertEquals("飲む", page(EntryTarget.Lookup("のむ")).head!!.headword)
    }

    @Test fun lookupFallsBackToDictionaryOnlyEntries() {
        val s = page(EntryTarget.Lookup("はまでら"))
        assertEquals(null, s.head) // the item's title heads its section
        assertTrue(s.word.target.startsWith("dict:"))
    }

    @Test fun unknownTargetsAreNotFound() {
        assertEquals(EntryUiState.NotFound, entryState(EntryTarget.Core(-1)))
    }

    /** A look-up the packs don't have still gets a page: the web tabs and the star work on it. */
    @Test fun unknownLookupIsAWordOfItsOwn() {
        val s = page(EntryTarget.Lookup(" ゐゑゐゑ "))
        assertEquals(EntryPage.unknown("ゐゑゐゑ", ""), s)
        assertEquals(WordRef("ゐゑゐゑ", "", "lookup:ゐゑゐゑ"), s.word)
        assertEquals(listOf("ゐゑゐゑ"), s.webQuery)
        assertTrue(s.sections.isEmpty())
    }
}
