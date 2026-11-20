package app.jisho.feature.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The handwriting panel remembers that the model was there, so it opens without ML Kit's slow check. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingViewModelTest {
    /** ML Kit stand-in: the model check waits until the test answers it. */
    private class FakeEngine : InkEngine {
        val check = CompletableDeferred<Boolean>()
        var downloads = 0
        override suspend fun isModelReady() = check.await()
        override suspend fun downloadModel() { downloads++ }
        override suspend fun recognize(strokes: List<List<InkPoint>>, width: Float, height: Float, preContext: String, max: Int) = emptyList<String>()
        override fun close() {}
    }

    private class FakeMemory(var ready: Boolean) : InkModelMemory {
        override suspend fun wasReady() = ready
        override suspend fun remember(ready: Boolean) { this.ready = ready }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun rememberedModelIsReadyBeforeTheCheckFinishes() {
        val engine = FakeEngine()
        val vm = HandwritingViewModel(engine, FakeMemory(ready = true))
        vm.checkModel()
        assertEquals(InkModelState.Ready, vm.state.value.model) // the slow check has not answered yet
        engine.check.complete(true)
        assertEquals(InkModelState.Ready, vm.state.value.model)
    }

    @Test fun modelGoneSinceLastTimeShowsDownloadAgain() {
        val engine = FakeEngine()
        val memory = FakeMemory(ready = true)
        val vm = HandwritingViewModel(engine, memory)
        vm.checkModel()
        engine.check.complete(false)
        assertEquals(InkModelState.NotDownloaded, vm.state.value.model)
        assertEquals(false, memory.ready)
    }

    @Test fun firstTimeWaitsForTheCheckAndRemembersIt() {
        val engine = FakeEngine()
        val memory = FakeMemory(ready = false)
        val vm = HandwritingViewModel(engine, memory)
        vm.checkModel()
        assertEquals(InkModelState.Checking, vm.state.value.model)
        engine.check.complete(true)
        assertEquals(InkModelState.Ready, vm.state.value.model)
        assertEquals(true, memory.ready)
    }

    @Test fun downloadIsRemembered() {
        val engine = FakeEngine()
        val memory = FakeMemory(ready = false)
        val vm = HandwritingViewModel(engine, memory)
        vm.checkModel()
        engine.check.complete(false)
        assertEquals(InkModelState.NotDownloaded, vm.state.value.model)
        vm.download()
        assertEquals(InkModelState.Ready, vm.state.value.model)
        assertEquals(1, engine.downloads)
        assertEquals(true, memory.ready)
    }
}
