package app.jisho.core.designsystem

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn] (no library:
 * the list is short and this is all it needs). The dragged row follows the finger; when its
 * middle passes over another reorderable row, [onMove] gets both rows' keys (the caller moves
 * the item in its own list); near the top or bottom edge the list scrolls. [onDone] runs when the finger
 * lifts, to save the order once.
 */
class ReorderState internal constructor(
    val list: LazyListState,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val canMoveTo: (key: Any) -> Boolean,
    private val onDone: () -> Unit,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    /** How far the dragged row is drawn from where the list puts it. */
    var offset by mutableFloatStateOf(0f)
        private set
    /** How far the finger has moved since the drag started, and which way it last moved. */
    private var travel = 0f
    private var heading = 0f

    fun start(key: Any) { dragging = key; offset = 0f; travel = 0f; heading = 0f }

    fun drag(dy: Float) {
        offset += dy; travel += kotlin.math.abs(dy)
        if (dy != 0f) heading = dy
        swapIfOver()
    }

    fun end() {
        if (dragging == null) return
        dragging = null; offset = 0f
        onDone()
    }

    /** The layout a swap was made in: until the list lays out again its positions are out of date. */
    private var swappedIn: Any? = null

    private fun swapIfOver() {
        val info = list.layoutInfo
        if (info === swappedIn) return
        val items = info.visibleItemsInfo
        val cur = items.firstOrNull { it.key == dragging } ?: return
        val middle = cur.offset + offset + cur.size / 2
        val target = items.firstOrNull {
            it.key != dragging && canMoveTo(it.key) && middle > it.offset && middle < it.offset + it.size
        } ?: return
        // Where the dragged row will sit after the swap, so it stays under the finger.
        val newTop = if (target.index > cur.index) target.offset + target.size - cur.size else target.offset
        offset -= newTop - cur.offset
        // The list keeps its first visible row by key: moving a row into or out of that slot would
        // scroll the list with it (the dragged row slid out of sight). Keep the position by index.
        val first = list.firstVisibleItemIndex
        if (cur.index == first || target.index == first) list.requestScrollToItem(first, list.firstVisibleItemScrollOffset)
        swappedIn = info
        onMove(cur.key, target.key)
    }

    /**
     * Scroll speed (px per frame) while the dragged row is near an edge; 0 elsewhere. Only once the
     * finger has moved toward that edge: a row picked up at the edge would otherwise start the list
     * scrolling under a still finger.
     */
    internal fun edgeSpeed(): Float {
        val info = list.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.key == dragging } ?: return 0f
        val top = cur.offset + offset
        val bottom = top + cur.size
        val zone = cur.size * 0.75f
        if (travel < cur.size * 0.25f) return 0f
        return when {
            heading < 0 && top < info.viewportStartOffset + zone -> -EDGE_SPEED
            heading > 0 && bottom > info.viewportEndOffset - zone -> EDGE_SPEED
            else -> 0f
        }
    }

    internal suspend fun scrollStep(speed: Float) {
        val moved = list.scrollBy(speed)
        offset += moved
        swapIfOver()
    }
}

private const val EDGE_SPEED = 12f

@Composable
fun rememberReorderState(
    list: LazyListState,
    canMoveTo: (key: Any) -> Boolean = { true },
    onDone: () -> Unit = {},
    onMove: (fromKey: Any, toKey: Any) -> Unit,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val can by rememberUpdatedState(canMoveTo)
    val done by rememberUpdatedState(onDone)
    val state = remember(list) { ReorderState(list, { a, b -> move(a, b) }, { can(it) }, { done() }) }
    LaunchedEffect(state.dragging) {
        while (state.dragging != null) {
            withFrameNanos { }
            val speed = state.edgeSpeed()
            if (speed != 0f) state.scrollStep(speed)
        }
    }
    return state
}

/** On a row: lifts it while dragged; other rows slide out of the way (pass animateItem() as [settle]). */
fun Modifier.reorderable(state: ReorderState, key: Any, settle: Modifier): Modifier =
    if (state.dragging == key) zIndex(1f).graphicsLayer { translationY = state.offset }.shadow(6.dp)
    else then(settle)

/** On a drag handle: dragging it moves the row at once. */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    )
}

/** On a whole row: hold, then drag. */
fun Modifier.longPressDrag(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(key) },
        onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    )
}
