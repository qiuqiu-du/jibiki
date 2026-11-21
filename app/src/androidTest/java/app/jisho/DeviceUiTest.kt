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
}
