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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusEvent
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.toSize
import java.util.concurrent.atomic.AtomicInteger
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
 * A list that can still scroll towards the key scrolls rather than be left or jumped over: from
 * inside it when nothing focusable is left in it that way, and from a floating control when the
 * nearest place to go is another floating control across the middle of it. Without that, the
 * cover and title of a playlist or an album stayed under the top bar once focus had gone up to
 * the bar, and where the cover filled all of the list between the top bar and the mini player,
 * the list could not be entered at all. And Up with nothing at all above, from content or from
 * the search pill or a top bar, goes round to the selected tab of the navigation bar.
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

/** Marks the navigation bar, whose selected tab Up reaches from the top of the screen; see [dpadTabBar]. */
val DpadTabBarKey = SemanticsPropertyKey<Boolean>("DpadTabBar")
private var SemanticsPropertyReceiver.dpadTabBar by DpadTabBarKey

/** Marks a part of the screen closed to the keys for now; see [blockFocusWhen]. */
val DpadBlockedKey = SemanticsPropertyKey<Boolean>("DpadBlocked")
private var SemanticsPropertyReceiver.dpadBlocked by DpadBlockedKey

/**
 * Marks a floating control, or a group of them, that is drawn over the screen's content: an arrow
 * key pressed on it moves to the nearest visible element anywhere on screen (see
 * [dpadOverlayEscape]), and content lying underneath it is never chosen.
 */
fun Modifier.dpadOverlay(): Modifier = semantics { dpadOverlay = true }.composed {
    // Counted in and out as focus enters and leaves, so an arrow key in ordinary content can skip
    // the search across the screen without walking it first. See [needsScreenSearch].
    val had = remember { BooleanArray(1) }
    DisposableEffect(Unit) { onDispose { if (had[0]) overlaysFocused.decrementAndGet() } }
    onFocusEvent { state ->
        if (state.hasFocus != had[0]) {
            had[0] = state.hasFocus
            if (state.hasFocus) overlaysFocused.incrementAndGet() else overlaysFocused.decrementAndGet()
        }
    }
}

/** How many floating controls hold focus right now; see [dpadOverlay]. */
private val overlaysFocused = AtomicInteger(0)

/**
 * Marks the navigation bar, whose selected tab Up goes round to when nothing is above the focused
 * element (see [dpadOverlayEscape]). Only the bar at the bottom of a phone: the rail beside a
 * wide screen is in reach from the side.
 */
fun Modifier.dpadTabBar(): Modifier = semantics { dpadTabBar = true }

/**
 * Counts the moves [blockFocusWhen] has refused and is making again itself, so a refused move is
 * not taken for a dead end.
 */
private val redirectedMoves = AtomicInteger(0)

/**
 * Whether an arrow key needs the search across the whole screen: only when focus is on a floating
 * control, or nowhere. Anywhere else Compose's own search is enough, and walking every element on
 * screen for each key press cost a slow phone a visible pause in long lists.
 */
fun needsScreenSearch(anythingFocused: Boolean, overlaysFocused: Int): Boolean =
    !anythingFocused || overlaysFocused > 0

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

/**
 * Whether an arrow key pressed in a list scrolls the list instead of moving focus out of it:
 * when nothing focusable is left in the list in [direction] ([furtherInList] false) and the list
 * can still scroll that way, going by its scroll [value] out of [maxValue]. Without it, the part
 * of a page above its first focusable element never came back into view from the keys: focus went
 * on up to the top bar and left the cover of a playlist or album underneath it. Only up and down,
 * and not for a list scrolling in reverse, which none of the pages with a header do.
 */
internal fun dpadScrollsInstead(
    direction: DpadDirection,
    value: Float,
    maxValue: Float,
    reversed: Boolean,
    furtherInList: Boolean,
): Boolean {
    if (furtherInList || reversed) return false
    return when (direction) {
        DpadDirection.Up -> value > 0f
        DpadDirection.Down -> value < maxValue
        DpadDirection.Left, DpadDirection.Right -> false
    }
}

/** How much of a list's height one press scrolls it by at most; see [dpadEdgeScrollDelta]. */
internal const val DpadEdgeScrollFraction = 0.3f

/**
 * How far a lazy list's scroll value moves per item before it: Compose reports the position of
 * one as the first visible item's index times this plus the offset into it, so the value is exact
 * only while the first item is scrolled less than this far out of view. That is Compose's own
 * estimate (1.9.4) and may change with it.
 */
internal const val LazyScrollValuePerItem = 500f

/**
 * How far one press scrolls a list of [height] whose scroll value is [value] out of [maxValue], when
 * [dpadScrollsInstead] says it should: [DpadEdgeScrollFraction] of its height, but never further
 * back than the list's start. What a list cannot take goes on to whatever is around it, and a pull
 * to refresh read the rest as a pull and stayed half drawn. A [lazy] list's value is exact only
 * below [LazyScrollValuePerItem], so further down it is not scrolled back at all, and Compose's
 * own search, which brings in the rows above, is left to it. A tall first item scrolled further
 * than that out of view is left to Compose too. Null for no scroll.
 */
internal fun dpadEdgeScrollDelta(direction: DpadDirection, height: Float, value: Float, maxValue: Float, lazy: Boolean): Float? {
    val step = height * DpadEdgeScrollFraction
    return when (direction) {
        DpadDirection.Up -> if (lazy && value >= LazyScrollValuePerItem) null else -minOf(step, value)
        // Past the end, nothing around a list takes the rest: a pull to refresh only pulls at the top.
        DpadDirection.Down -> if (lazy) step else minOf(step, maxValue - value)
        DpadDirection.Left, DpadDirection.Right -> null
    }?.takeIf { it != 0f }
}

/**
 * Whether a move from a floating control at [from] to [target], another floating control, or to
 * nothing ([target] null), would jump over the middle of a [list] lying between them. On a small
 * screen the cover of a playlist can fill all of the list that shows between the top bar and the
 * mini player, so the search across the screen found nothing in it, and Down from the back button
 * went straight to the mini player, Up from there straight back: the list could not be entered.
 * When the list can still scroll that way, it scrolls instead (see [dpadEdgeScrollDelta]), until
 * something in it comes into view. A move between neighbours, like from the navigation bar up to
 * the mini player, or from the floating button down to it, does not cross the middle.
 */
internal fun dpadJumpsOverList(from: Rect, target: Rect?, list: Rect, direction: DpadDirection): Boolean {
    val middle = list.center.y
    return when (direction) {
        DpadDirection.Down -> from.bottom <= middle && (target == null || target.top >= middle)
        DpadDirection.Up -> from.top >= middle && (target == null || target.bottom <= middle)
        DpadDirection.Left, DpadDirection.Right -> false
    }
}

/**
 * Whether Up goes round to the navigation bar: the search for something above found nothing
 * ([moved] false), and [blockFocusWhen] has not taken the move over ([redirected]). In ordinary
 * content that search is Compose's own, which brings in the rows above, so a list scrolled down
 * still moves up through its rows first; only at the real top is there nothing. On a floating
 * control it is the search across the screen, which finds nothing only above the topmost ones,
 * the search pill and a screen's top bar. Those sit above the first row of every screen, so with
 * content alone the wrap would never come.
 */
internal fun dpadWrapsToTabs(direction: DpadDirection, moved: Boolean, redirected: Boolean): Boolean =
    direction == DpadDirection.Up && !moved && !redirected

/** A tab of the navigation bar: whether it is the selected one, and whether it can take focus now. */
internal class DpadTab(val selected: Boolean, val usable: Boolean)

/**
 * Which of [tabs] Up goes round to: the selected one, or the first one when none is selected, as
 * on a screen opened from a tab. Never one that cannot take focus, such as the whole bar while the
 * expanded player covers it. Null when there is none.
 */
internal fun dpadTabToFocus(tabs: List<DpadTab>): Int? {
    val usable = tabs.indices.filter { tabs[it].usable }
    return usable.firstOrNull { tabs[it].selected } ?: usable.firstOrNull()
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
 * focusable element in that direction anywhere on screen, and, when a key reaches it with nothing
 * inside focused, by focusing the first element in reading order. Goes on the root of the window's
 * content. Keys pressed anywhere else, and inside a list that scrolls within a floating control
 * such as the search suggestions, are left to Compose's own search.
 *
 * A move from one floating control across the middle of a list to another, or to nothing,
 * scrolls the list instead while it can still scroll that way (see [dpadJumpsOverList]).
 *
 * Up and down in ordinary content get two more answers: a list that can still scroll that way,
 * with nothing focusable left in it in that direction, scrolls instead of letting focus out (see
 * [dpadScrollsInstead]), and Up with nothing above goes round to the navigation bar (see
 * [dpadWrapsToTabs]), as it does from the topmost floating controls.
 */
fun Modifier.dpadOverlayEscape(): Modifier = composed {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val anythingFocused = remember { BooleanArray(1) }
    onFocusEvent { anythingFocused[0] = it.hasFocus }.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = event.dpadDirection() ?: return@onPreviewKeyEvent false
        if (!needsScreenSearch(anythingFocused[0], overlaysFocused.get())) return@onPreviewKeyEvent false
        val owner = (view as? RootForTest)?.semanticsOwner ?: return@onPreviewKeyEvent false
        when (moveFromOverlay(owner.unmergedRootSemanticsNode, direction, fromAnywhere = false)) {
            DpadMove.Moved -> true
            DpadMove.NotOurs -> false
            DpadMove.NothingThere ->
                dpadWrapsToTabs(direction, moved = false, redirected = false) && focusTabBar(view)
        }
    }.onKeyEvent { event ->
        // Up and down in ordinary content that nothing focused has taken. The rest stays with
        // Compose's own handling, which comes after this.
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val direction = event.dpadDirection() ?: return@onKeyEvent false
        if (direction != DpadDirection.Up && direction != DpadDirection.Down) return@onKeyEvent false
        if (needsScreenSearch(anythingFocused[0], overlaysFocused.get())) return@onKeyEvent false
        if (scrollListInstead(view, direction)) return@onKeyEvent true
        if (direction != DpadDirection.Up) return@onKeyEvent false
        val redirects = redirectedMoves.get()
        val moved = focusManager.moveFocus(FocusDirection.Up)
        if (dpadWrapsToTabs(direction, moved, redirected = redirectedMoves.get() != redirects)) {
            focusTabBar(view)
        }
        // Taken either way: Compose's own handling would only search again and find the same.
        true
    }
}

/**
 * Where a node lies in the window, unclipped, as Compose's focus search measures it. Empty for one
 * not placed, such as a row a lazy list has composed ahead of need: it reports a place at the top
 * of the list it is not at.
 */
private fun SemanticsNode.unclippedBounds(): Rect =
    if (layoutInfo.isAttached && layoutInfo.isPlaced) Rect(positionInRoot, size.toSize()) else Rect.Zero

private fun SemanticsConfiguration.isFocusable(): Boolean =
    SemanticsProperties.Focused in this && SemanticsActions.RequestFocus in this && SemanticsProperties.Disabled !in this

/**
 * Scrolls the list holding focus by [DpadEdgeScrollFraction] of its height when [dpadScrollsInstead]
 * says so, and says whether it did. Only the branch of the tree around the focused element is
 * walked, then the list, and that only while the list can still scroll that way, so in the middle
 * of a long list this stops at the first row before the focused one.
 */
private fun scrollListInstead(view: View, direction: DpadDirection): Boolean {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return false
    val focusRect = android.graphics.Rect().also { view.getFocusedRect(it) }
    if (focusRect.isEmpty) return false
    val from = Rect(focusRect.left.toFloat(), focusRect.top.toFloat(), focusRect.right.toFloat(), focusRect.bottom.toFloat())

    var focused: SemanticsNode? = null
    var list: SemanticsNode? = null
    fun descend(node: SemanticsNode, listSoFar: SemanticsNode?) {
        if (focused != null) return
        val config = node.config
        if (config.getOrElse(DpadOverlayKey) { false } || config.getOrElse(DpadBlockedKey) { false }) return
        val scroller = if (SemanticsProperties.VerticalScrollAxisRange in config && SemanticsActions.ScrollBy in config) node else listSoFar
        if (config.getOrElse(SemanticsProperties.Focused) { false }) {
            focused = node
            list = scroller
            return
        }
        node.children.forEach { if (it.unclippedBounds().overlaps(from)) descend(it, scroller) }
    }
    descend(owner.unmergedRootSemanticsNode, null)
    val target = focused ?: return false
    val scroller = list ?: return false

    val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
    val value = range.value()
    val maxValue = range.maxValue()
    if (!dpadScrollsInstead(direction, value, maxValue, range.reverseScrolling, furtherInList = false)) return false

    fun anyFurther(node: SemanticsNode): Boolean {
        val config = node.config
        if (config.getOrElse(DpadBlockedKey) { false }) return false
        if (node.id != target.id && config.isFocusable()) {
            val bounds = node.unclippedBounds()
            if (!bounds.isEmpty && isDpadCandidate(from, bounds, direction)) return true
        }
        return node.children.any { anyFurther(it) }
    }
    if (!dpadScrollsInstead(direction, value, maxValue, range.reverseScrolling, anyFurther(scroller))) return false

    val lazy = SemanticsActions.ScrollToIndex in scroller.config
    val delta = dpadEdgeScrollDelta(direction, scroller.boundsInRoot.height, value, maxValue, lazy) ?: return false
    return scroller.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) ?: false
}

/** Focuses the tab [dpadTabToFocus] picks on the bar marked with [dpadTabBar], if there is one. */
private fun focusTabBar(view: View): Boolean {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return false
    val nodes = ArrayList<SemanticsNode>()
    val tabs = ArrayList<DpadTab>()
    fun visit(node: SemanticsNode, inBar: Boolean) {
        val config = node.config
        // Closed to the keys, such as the bar while the expanded player covers it.
        if (config.getOrElse(DpadBlockedKey) { false }) return
        val bar = inBar || config.getOrElse(DpadTabBarKey) { false }
        if (bar && SemanticsProperties.Focused in config && SemanticsActions.RequestFocus in config) {
            nodes += node
            tabs += DpadTab(
                selected = config.getOrElse(SemanticsProperties.Selected) { false },
                usable = SemanticsProperties.Disabled !in config && !node.boundsInRoot.isEmpty,
            )
            return
        }
        node.children.forEach { visit(it, bar) }
    }
    visit(owner.unmergedRootSemanticsNode, inBar = false)
    val index = dpadTabToFocus(tabs) ?: return false
    return nodes[index].config[SemanticsActions.RequestFocus].action?.invoke() ?: false
}

/**
 * Moves focus in [direction] by looking across the whole screen, see [dpadOverlayEscape]; with
 * [fromAnywhere], from wherever focus is rather than only from a floating control. With nothing
 * focused, focuses the first element in reading order, whatever the direction, null included.
 */
private fun dpadMove(view: View, direction: DpadDirection?, fromAnywhere: Boolean): Boolean {
    val owner = (view as? RootForTest)?.semanticsOwner ?: return false
    return moveFromOverlay(owner.unmergedRootSemanticsNode, direction, fromAnywhere) == DpadMove.Moved
}

/** What [moveFromOverlay] made of a key: a move, nothing in that direction, or a key it leaves to Compose. */
private enum class DpadMove { Moved, NothingThere, NotOurs }

private fun moveFromOverlay(root: SemanticsNode, direction: DpadDirection?, fromAnywhere: Boolean): DpadMove {
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
    // Lists in the screen's content, which a move from a floating control may scroll instead of
    // jumping over; see [dpadJumpsOverList].
    val lists = ArrayList<SemanticsNode>()

    fun visit(node: SemanticsNode, inOverlay: Boolean, scrollerInOverlay: Boolean) {
        val config = node.config
        // Closed to the keys, so neither a place to go nor, being out of sight, cover for anything.
        if (config.getOrElse(DpadBlockedKey) { false }) return
        // Composed ahead of need by a lazy list and not placed: not on screen at all.
        if (!node.layoutInfo.isPlaced) return
        val isOverlay = config.getOrElse(DpadOverlayKey) { false }
        val overlay = inOverlay || isOverlay
        val scroller = if (isOverlay) false else scrollerInOverlay || (overlay && alongAxis in config)
        if (isOverlay) overlays += node.boundsInRoot
        if (!overlay && SemanticsProperties.VerticalScrollAxisRange in config && SemanticsActions.ScrollBy in config) {
            lists += node
        }
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
        return if (first?.requestFocus() == true) DpadMove.Moved else DpadMove.NothingThere
    }
    if (direction == null) return DpadMove.NotOurs
    if (!fromAnywhere && (!from.inOverlay || focusedInScroller)) return DpadMove.NotOurs

    val candidates = visible.filter { it !== from }
    val targets = dpadTargets(from.bounds, direction, candidates.map { it.bounds })
    val best = targets.firstOrNull()?.let { candidates[it] }
    if (from.inOverlay && (best == null || best.inOverlay) &&
        scrollListBetween(lists, overlays, from.bounds, best?.bounds, direction)
    ) {
        return DpadMove.Moved
    }
    for (index in targets) {
        if (candidates[index].requestFocus()) return DpadMove.Moved
    }
    // A text field keeps an arrow key nothing else wants: it moves the cursor, and keyboards with
    // arrow keys send them while typing.
    if (SemanticsProperties.EditableText in from.node.config) return DpadMove.NotOurs
    return DpadMove.NothingThere
}

/**
 * Scrolls the largest of [lists] in line with [from] when a move to [target] would jump over its
 * middle and it can still scroll in [direction]; see [dpadJumpsOverList]. Says whether it did.
 * A list lying under one of [overlays], such as the feed under the open search, is out of sight
 * and never scrolled.
 */
private fun scrollListBetween(
    lists: List<SemanticsNode>,
    overlays: List<Rect>,
    from: Rect,
    target: Rect?,
    direction: DpadDirection,
): Boolean {
    val list = lists
        .filter {
            val b = it.boundsInRoot
            !b.isEmpty && b.left < from.right && b.right > from.left && !hiddenUnderOverlay(b, overlays)
        }
        .maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height } ?: return false
    val bounds = list.boundsInRoot
    if (!dpadJumpsOverList(from, target, bounds, direction)) return false
    val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
    val value = range.value()
    val maxValue = range.maxValue()
    if (!dpadScrollsInstead(direction, value, maxValue, range.reverseScrolling, furtherInList = false)) return false
    val lazy = SemanticsActions.ScrollToIndex in list.config
    val delta = dpadEdgeScrollDelta(direction, bounds.height, value, maxValue, lazy) ?: return false
    return list.config[SemanticsActions.ScrollBy].action?.invoke(0f, delta) ?: false
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
                    redirectedMoves.incrementAndGet()
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

/**
 * The look Material's Text gives a link that brings none of its own, primary and underlined, plus a
 * highlight while the link holds focus. Without it, the artist link in the header of an album or a
 * playlist took focus from the arrow keys and showed nothing, so a press seemed lost. Focus comes
 * to a link from the keys only, so touch use sees the same link as before.
 */
@Composable
fun linkStylesWithFocus(): TextLinkStyles {
    val primary = MaterialTheme.colorScheme.primary
    return remember(primary) {
        TextLinkStyles(
            style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline),
            focusedStyle = SpanStyle(background = primary.copy(alpha = 0.24f)),
        )
    }
}
