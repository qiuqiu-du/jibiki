package app.jisho.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A finger must move this many times farther sideways than up/down for the gesture to switch
 * pages (2 = within ~27° of horizontal; Android's ViewPager uses the same rule). Higher: fewer
 * accidental switches, but a sideways swipe has to be straighter.
 */
private const val SWIPE_RATIO = 2f

/** Release speed above which a swipe goes to the next page however short it was. */
private val FLING_SPEED = 400.dp

/**
 * Sideways swipes switch [pager]'s pages, decided the way mainstream apps do: watch the first
 * few pixels of a gesture (the system "touch slop", ~8 dp) and lock it to one direction for
 * the rest of it. Mostly vertical: left alone for reading, however it drifts later. Clearly
 * sideways: it moves the pages, and the page under the finger (a WebView too) gets a cancel.
 *
 * The pager's own swipe can't do this over a WebView: it only measures sideways movement, and
 * as the WebView's scrolling isn't visible to Compose, a vertical read that drifts 8 dp sideways
 * looked like a page swipe. Here every touch is seen first (the Initial pass), before any page.
 */
fun Modifier.pageSwipe(pager: PagerState, enabled: Boolean): Modifier = if (!enabled) this else composed {
    val scope = rememberCoroutineScope()
    pointerInput(pager) {
        val slop = viewConfiguration.touchSlop
        val fling = FLING_SPEED.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = pager.currentPage
            var dx = 0f
            var dy = 0f
            var sideways = false
            val tracker = VelocityTracker()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1 && !sideways) return@awaitEachGesture // pinch zoom
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.position - change.previousPosition
                if (!sideways) {
                    dx += delta.x
                    dy += delta.y
                    if (abs(dx) <= slop && abs(dy) <= slop) continue // not decided yet
                    if (abs(dx) <= SWIPE_RATIO * abs(dy)) return@awaitEachGesture // vertical: not ours
                    sideways = true
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                pager.dispatchRawDelta(-delta.x)
                event.changes.forEach { it.consume() } // the page below stops scrolling
            }
            if (sideways) {
                val v = tracker.calculateVelocity().x
                val target = when {
                    v < -fling -> start + 1
                    v > fling -> start - 1
                    else -> pager.currentPage // past halfway or not
                }.coerceIn(0, pager.pageCount - 1)
                scope.launch { pager.animateScrollToPage(target) }
            }
        }
    }
}
