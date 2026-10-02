/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.max

/*
 * Keypad and D-pad navigation.
 *
 * Compose moves focus for the arrow keys by comparing the focused element with its siblings in
 * the focus tree, and a scrolling list counts as one sibling the size of the whole list. Floating
 * controls sit on top of that list: the search pill, a screen's top bar, the floating button, the
 * mini player and the navigation bar. From any of them the list is not "above" or "below" but all
 * around, so it is never a candidate, and focus that once reached a floating control could only
 * hop between floating controls until a finger scrolled them away. The same reason kept the mini
 * player's buttons out of reach: the sheet behind them is one focusable box covering the
 * navigation bar as well.
 *
 * Two general fixes follow. Floating controls are marked with [dpadOverlay], and an arrow key
 * pressed on one of them is answered by [dpadOverlayEscape], which picks the nearest visible
 * focusable element in that direction across the whole screen, ignoring how the tree is grouped
 * and skipping anything hidden underneath a floating control. Everywhere else Compose's own search
 * is kept, since it is the one that can scroll a list to reach rows not composed yet. And
 * [dpadBringIntoView] scrolls the row or card that receives focus towards the middle
 * of its list, instead of the default of just inside the edge, which on a phone is underneath the
 * mini player or the top bar.
 *
 * Touch use is untouched: Compose gives focus to clickable elements only in keyboard mode, the
 * scrolling rule falls back to the default unless the last input came from keys, and the key
 * handler only answers keys.
 */

/** A direction an arrow key moves focus in. */
enum class DpadDirection { Up, Down, Left, Right }

/** Marks a node as a floating control drawn over the screen's content. */
val DpadOverlayKey = SemanticsPropertyKey<Boolean>("DpadOverlay")
private var SemanticsPropertyReceiver.dpadOverlay by DpadOverlayKey

/** Marks a part of the screen closed to the keys for now; see [blockFocusWhen]. */
val DpadBlockedKey = SemanticsPropertyKey<Boolean>("DpadBlocked")
private var SemanticsPropertyReceiver.dpadBlocked by DpadBlockedKey

/**
 * Marks a floating control, or a group of them, that is drawn over the screen's content: an arrow
 * key pressed on it moves to the nearest visible element anywhere on screen (see
 * [dpadOverlayEscape]), and content lying underneath it is never chosen.
 */
fun Modifier.dpadOverlay(): Modifier = semantics { dpadOverlay = true }

/**
 * Whether [candidate] lies in [direction] from [from]: it must start beyond the edge being moved
 * across and reach further than [from] does, the rule Android's own FocusFinder uses. A rectangle
 * that encloses [from] is never a candidate.
 */
internal fun isDpadCandidate(from: Rect, candidate: Rect, direction: DpadDirection): Boolean = when (direction) {
    DpadDirection.Up -> (from.bottom > candidate.bottom || from.top >= candidate.bottom) && from.top > candidate.top
    DpadDirection.Down -> (from.top < candidate.top || from.bottom <= candidate.top) && from.bottom < candidate.bottom
    DpadDirection.Left -> (from.right > candidate.right || from.left >= candidate.right) && from.left > candidate.left
    DpadDirection.Right -> (from.left < candidate.left || from.right <= candidate.left) && from.right < candidate.right
}

/** Whether [candidate] overlaps [from] across the direction of travel, so moving straight on reaches it. */
private fun inBeam(from: Rect, candidate: Rect, direction: DpadDirection): Boolean = when (direction) {
    DpadDirection.Up, DpadDirection.Down -> candidate.right > from.left && candidate.left < from.right
    DpadDirection.Left, DpadDirection.Right -> candidate.bottom > from.top && candidate.top < from.bottom
}

/** How far [candidate] lies along the direction of travel from [from]'s leading edge, never negative. */
private fun majorDistance(from: Rect, candidate: Rect, direction: DpadDirection): Float = max(
    0f,
    when (direction) {
        DpadDirection.Up -> from.top - candidate.bottom
        DpadDirection.Down -> candidate.top - from.bottom
        DpadDirection.Left -> from.left - candidate.right
        DpadDirection.Right -> candidate.left - from.right
    }
)

/** How far the centres of [from] and [candidate] lie apart across the direction of travel. */
private fun minorDistance(from: Rect, candidate: Rect, direction: DpadDirection): Float = when (direction) {
    DpadDirection.Up, DpadDirection.Down -> abs(from.center.x - candidate.center.x)
    DpadDirection.Left, DpadDirection.Right -> abs(from.center.y - candidate.center.y)
}

/** How far [candidate]'s far edge lies along the direction of travel from [from]'s leading edge. */
private fun majorDistanceToFarEdge(from: Rect, candidate: Rect, direction: DpadDirection): Float = max(
    1f,
    when (direction) {
        DpadDirection.Up -> from.top - candidate.top
        DpadDirection.Down -> candidate.bottom - from.bottom
        DpadDirection.Left -> from.left - candidate.left
        DpadDirection.Right -> candidate.right - from.right
    }
)

/** Whether [candidate] lies wholly past [from]'s leading edge. */
private fun whollyBeyond(from: Rect, candidate: Rect, direction: DpadDirection): Boolean = when (direction) {
    DpadDirection.Up -> from.top >= candidate.bottom
    DpadDirection.Down -> from.bottom <= candidate.top
    DpadDirection.Left -> from.left >= candidate.right
    DpadDirection.Right -> from.right <= candidate.left
}

/**
 * Whether [a] wins over [b] for being in the beam when [b] is not. Sideways that is always so;
 * up and down only while [b] is not wholly nearer, so the buttons beside a cover beat a bar far
 * below it that merely happens to line up with the back button.
 */
private fun beamBeats(from: Rect, a: Rect, b: Rect, direction: DpadDirection): Boolean {
    if (inBeam(from, b, direction) || !inBeam(from, a, direction)) return false
    if (!whollyBeyond(from, b, direction)) return true
    if (direction == DpadDirection.Left || direction == DpadDirection.Right) return true
    return majorDistance(from, a, direction) < majorDistanceToFarEdge(from, b, direction)
}

private fun weightedDistance(from: Rect, candidate: Rect, direction: DpadDirection): Float {
    val major = majorDistance(from, candidate, direction)
    val minor = minorDistance(from, candidate, direction)
    return 13 * major * major + minor * minor
}

/** Whether [a] is a better target than [b], both being candidates: FocusFinder's comparison. */
private fun better(from: Rect, a: Rect, b: Rect, direction: DpadDirection): Boolean = when {
    beamBeats(from, a, b, direction) -> true
    beamBeats(from, b, a, direction) -> false
    else -> weightedDistance(from, a, direction) < weightedDistance(from, b, direction)
}

/**
 * Up to [limit] candidates in [direction] from [from], best first, as indices into [candidates],
 * ranked the way Android's FocusFinder ranks views: one in the beam wins unless another is wholly
 * nearer, then the nearest along the direction of travel, weighed thirteen times as heavily as
 * the drift across it.
 */
internal fun dpadTargets(from: Rect, direction: DpadDirection, candidates: List<Rect>, limit: Int = 4): List<Int> {
    val remaining = candidates.indices
        .filter { !candidates[it].isEmpty && isDpadCandidate(from, candidates[it], direction) }
        .toMutableList()
    val ranked = ArrayList<Int>()
    while (remaining.isNotEmpty() && ranked.size < limit) {
        val best = remaining.reduce { best, next ->
            if (better(from, candidates[next], candidates[best], direction)) next else best
        }
        ranked += best
        remaining -= best
    }
    return ranked
}

/** Whether [candidate], which is not part of a floating control, lies underneath one of [overlays]. */
internal fun hiddenUnderOverlay(candidate: Rect, overlays: List<Rect>): Boolean =
    overlays.any { it.contains(candidate.center) }

/**
 * How far to scroll for an item of [size] at [offset] that has just taken focus in a container of
 * [containerSize]. Below the [pivot] it is brought up until its centre sits there, so the row
 * after it is in view too and nothing at the bottom, the mini player above all, covers it. Above
 * the pivot it stays put unless it reaches into the top [topZone] of the container, where a top
 * bar floats, and then it is brought down just clear of that.
 *
 * Only that far down, rather than back to the pivot, because at the start of a list no scroll is
 * possible, and what a list cannot take goes on to whatever is around it: a pull to refresh read
 * it as a pull. The first rows of a list sit below its top padding, outside the zone, and ask for
 * nothing.
 *
 * An item too wide to be centred without losing an edge, like a card nearly the width of its row,
 * is kept whole instead, and one larger than the container shows its start.
 */
internal fun dpadScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    pivot: Float = DpadPivot,
    topZone: Float = DpadTopZone,
): Float {
    val target = if (size >= containerSize) 0f
    else (containerSize * pivot - size / 2f).coerceIn(0f, containerSize - size)
    val toPivot = offset - target
    if (toPivot > 0f) return toPivot
    val clearOfTop = containerSize * topZone
    return if (offset < clearOfTop) max(offset - clearOfTop, toPivot) else 0f
}

/** Where a focused item's centre is kept: a little above the middle, clear of a top bar and of the mini player. */
internal const val DpadPivot = 0.4f

/** The top part of a list, where a top bar floats; see [dpadScrollDistance]. */
internal const val DpadTopZone = 0.18f

/** How far one press of left or right on the player's seek bar moves. */
internal const val DpadSeekStepMs = 10_000L

/**
 * Where one press of left or right on the seek bar lands: [DpadSeekStepMs] either way from
 * [position], kept inside the song. An unknown [duration] (negative, as Media3's TIME_UNSET is)
 * only stops it going below zero.
 */
internal fun dpadSeekTarget(position: Long, duration: Long, forward: Boolean): Long {
    val target = if (forward) position + DpadSeekStepMs else position - DpadSeekStepMs
    return if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)
}

/**
 * Whether a list of [containerSize] gets the pivot of [dpadScrollDistance] rather than the default
 * of scrolling as little as possible: only one longer than the window is wide. That is a list
 * running down a portrait screen, the kind the floating bars cover the ends of. A row scrolling
 * sideways can never be wider than the window, and centring the menu button at the end of a song
 * in a row of songs pulled the title out of sight, so rows keep the default, as do lists in
 * dialogs and everything in landscape, where nothing floats over the ends of a list.
 */
internal fun dpadPivots(containerSize: Float, windowWidth: Float): Boolean = containerSize > windowWidth

/**
 * Bring-into-view for keys: a focused row is scrolled to [DpadPivot] of its list rather than just
 * inside the list's edge, which on a phone is underneath the floating bars. Falls back to
 * [default] while the last input was touch, so a text field brought into view above the keyboard
 * behaves as it always has, and for lists that do not pivot; see [dpadPivots].
 */
private class DpadBringIntoViewSpec(
    private val inputModeManager: InputModeManager,
    private val windowInfo: WindowInfo,
    private val default: BringIntoViewSpec,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (inputModeManager.inputMode == InputMode.Keyboard &&
            dpadPivots(containerSize, windowInfo.containerSize.width.toFloat())
        ) dpadScrollDistance(offset, size, containerSize)
        else default.calculateScrollDistance(offset, size, containerSize)
}

/** Provides [DpadBringIntoViewSpec] to everything that scrolls inside, for a CompositionLocalProvider over the app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun dpadBringIntoView(): ProvidedValue<BringIntoViewSpec> {
    val inputModeManager = LocalInputModeManager.current
    val windowInfo = LocalWindowInfo.current
    val default = LocalBringIntoViewSpec.current
    val spec = remember(inputModeManager, windowInfo, default) {
        DpadBringIntoViewSpec(inputModeManager, windowInfo, default)
    }
    return LocalBringIntoViewSpec provides spec
}

private fun KeyEvent.dpadDirection(): DpadDirection? = when (key) {
    Key.DirectionUp -> DpadDirection.Up
    Key.DirectionDown -> DpadDirection.Down
    Key.DirectionLeft -> DpadDirection.Left
    Key.DirectionRight -> DpadDirection.Right
    else -> null
}

/** A focusable element found on screen, and whether it belongs to a floating control. */
private class Focusable(val node: SemanticsNode, val bounds: Rect, val inOverlay: Boolean)

/**
 * Answers an arrow key pressed on a floating control (see [dpadOverlay]) by moving to the nearest
 * focusable element in that direction anywhere on screen, and an arrow key pressed while nothing
 * has focus by focusing the first element in reading order. Goes on the root of the window's
 * content. Keys pressed anywhere else, and inside a list that scrolls within a floating control
 * such as the search suggestions, are left to Compose's own search.
 */
fun Modifier.dpadOverlayEscape(): Modifier = composed {
    val view = LocalView.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = event.dpadDirection() ?: return@onPreviewKeyEvent false
        dpadMove(view, direction, fromAnywhere = false)
    }
}

/**
 * Moves focus in [direction] by looking across the whole screen, see [dpadOverlayEscape]; with
 * [fromAnywhere], from wherever focus is rather than only from a floating control. With nothing
 * focused, focuses the first element in reading order, whatever the direction, null included.
 */
private fun dpadMove(view: View, direction: DpadDirection?, fromAnywhere: Boolean): Boolean {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return false
    return moveFromOverlay(owner.unmergedRootSemanticsNode, direction, fromAnywhere)
}

private fun moveFromOverlay(root: SemanticsNode, direction: DpadDirection?, fromAnywhere: Boolean): Boolean {
    val focusables = ArrayList<Focusable>()
    val overlays = ArrayList<Rect>()
    var focused: Focusable? = null
    // Whether a list scrolls in the direction of travel between the focused element and the
    // floating control it belongs to, like the search suggestions under the search field. Moving
    // along such a list is Compose's, which can bring in rows that are not composed yet. Across
    // it, like up or down from a row of chips, it is ours.
    val alongAxis = when (direction) {
        DpadDirection.Up, DpadDirection.Down -> SemanticsProperties.VerticalScrollAxisRange
        DpadDirection.Left, DpadDirection.Right, null -> SemanticsProperties.HorizontalScrollAxisRange
    }
    var focusedInScroller = false

    fun visit(node: SemanticsNode, inOverlay: Boolean, scrollerInOverlay: Boolean) {
        val config = node.config
        // Closed to the keys, so neither a place to go nor, being out of sight, cover for anything.
        if (config.getOrElse(DpadBlockedKey) { false }) return
        val isOverlay = config.getOrElse(DpadOverlayKey) { false }
        val overlay = inOverlay || isOverlay
        val scroller = if (isOverlay) false else scrollerInOverlay || (overlay && alongAxis in config)
        if (isOverlay) overlays += node.boundsInRoot
        if (SemanticsProperties.Focused in config && SemanticsActions.RequestFocus in config &&
            SemanticsProperties.Disabled !in config
        ) {
            val item = Focusable(node, node.boundsInRoot, overlay)
            if (config[SemanticsProperties.Focused]) {
                focused = item
                focusedInScroller = scroller
            }
            focusables += item
        }
        node.children.forEach { visit(it, overlay, scroller) }
    }
    visit(root, inOverlay = false, scrollerInOverlay = false)

    val visible = focusables.filter {
        !it.bounds.isEmpty && (it.inOverlay || !hiddenUnderOverlay(it.bounds, overlays))
    }
    val from = focused
    if (from == null) {
        // Nothing has focus, typically because what had it has just gone: the queue opened over
        // the button that opened it, say. Compose would start from the top left of the whole
        // window, which may be a part closed to the keys, and then give up.
        val first = visible.minWithOrNull(compareBy<Focusable> { it.bounds.top }.thenBy { it.bounds.left })
        return first?.requestFocus() ?: false
    }
    if (direction == null) return false
    if (!fromAnywhere && (!from.inOverlay || focusedInScroller)) return false

    val candidates = visible.filter { it !== from }
    for (index in dpadTargets(from.bounds, direction, candidates.map { it.bounds })) {
        if (candidates[index].requestFocus()) return true
    }
    return false
}

private fun Focusable.requestFocus(): Boolean =
    node.config[SemanticsActions.RequestFocus].action?.invoke() ?: false

/**
 * Keeps focus out of everything inside while [blocked], for the screen underneath the expanded
 * player, the queue or the walkthrough. Without it the arrow keys reach controls that cannot be
 * seen, because they are still composed underneath.
 *
 * Compose's own search does not skip a part closed this way: when the nearest element in the
 * direction of travel is inside, it stops there and focus stays put, even with a visible
 * element just beyond. So a refused move is made again, a moment later, by the search across
 * the screen, which leaves closed parts out from the start.
 */
fun Modifier.blockFocusWhen(blocked: Boolean): Modifier = composed {
    val view = LocalView.current
    semantics { if (blocked) dpadBlocked = true }
        .focusProperties {
            onEnter = {
                if (blocked) {
                    cancelFocusChange()
                    val direction = when (requestedFocusDirection) {
                        FocusDirection.Up -> DpadDirection.Up
                        FocusDirection.Down -> DpadDirection.Down
                        FocusDirection.Left -> DpadDirection.Left
                        FocusDirection.Right -> DpadDirection.Right
                        else -> null
                    }
                    view.post { dpadMove(view, direction, fromAnywhere = true) }
                }
            }
        }
        .focusGroup()
}

/**
 * An element that acts on the centre key but takes no touches: it can be focused only while the
 * last input came from keys, so it never changes what a finger does. For an area whose touch
 * handling belongs to something around it, like the mini player's title, which opens the player.
 */
fun Modifier.keyboardClickable(onClick: () -> Unit): Modifier = composed {
    val inputModeManager = LocalInputModeManager.current
    val interactionSource = remember { MutableInteractionSource() }
    this
        .focusProperties { canFocus = inputModeManager.inputMode == InputMode.Keyboard }
        .onKeyEvent {
            // Both halves are taken, so the clickable around this never sees a press it would
            // answer as well.
            if (it.key != Key.DirectionCenter && it.key != Key.Enter && it.key != Key.NumPadEnter) {
                return@onKeyEvent false
            }
            if (it.type == KeyEventType.KeyUp) onClick()
            true
        }
        .focusable(interactionSource = interactionSource)
        .indication(interactionSource, LocalIndication.current)
}
