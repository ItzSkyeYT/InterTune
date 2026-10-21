/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.focus.focusProperties
import com.dd3boh.outertune.ui.utils.dpadOverlay

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import com.dd3boh.outertune.constants.BottomSheetAnimationSpec
import com.dd3boh.outertune.constants.BottomSheetSoftAnimationSpec
import com.dd3boh.outertune.constants.BackAnimationsKey
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.constants.MinMiniPlayerHeight
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.NavigationBarAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

/**
 * Bottom Sheet
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    /**
     * Stops the sheet being dragged below its collapsed height, so it does not slide away under
     * the finger at all.
     *
     * Deliberately a separate flag rather than being inferred from a null [onDismiss]. Plenty of
     * sheets are simply not dismissible, the queue sheet among them, and those should keep their
     * existing rubber band. This is for the narrower case of a sheet that COULD be dismissed but
     * where the user has asked for that to be switched off.
     */
    pinAtCollapsed: Boolean = false,
    /**
     * Where the collapsed sheet is, as its left edge and its width, when that is not the whole
     * width it was given: the mini player of a phone on its side (Landscape.kt).
     *
     * Collapsed, the sheet itself is then only that wide, and not only what it draws. It takes
     * every touch inside its bounds, so a sheet the width of the window with a narrow panel drawn
     * in it would leave the rows showing either side of the panel dead to a tap. Once it starts
     * to open it is the whole width again, and the collapsed content stays where it was while it
     * fades.
     */
    collapsedSpan: Pair<Dp, Dp>? = null,
    /**
     * The sheet a Back moves: this one, unless it is told otherwise. The queue's sheet is given the
     * player's when Back from the queue closes the player (issue #30), and then a Back closes the
     * whole player in one step and leaves the queue open in it.
     *
     * The handler stays this sheet's. The queue's is added as the player opens, so it still goes
     * before any screen's under the player, and a selection or a search in the queue, added later
     * again, goes before it. It stands aside while either sheet is down or on its way down.
     */
    backMoves: BottomSheetState = state,
    collapsedContent: @Composable BoxScope.() -> Unit,
    collapsedBackgroundColor: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current

    /**
     * The gesture below is keyed on [state] alone, and Compose only restarts a running pointer
     * input coroutine when a key changes or when the handler's *class* changes (see
     * SuspendingPointerInputModifierNodeImpl.update). Swapping [onDismiss] between a lambda and
     * null yields a new instance of the same generated class, so the live gesture would go on
     * invoking whichever callback it captured the first time the sheet was touched. Reading it
     * through a State keeps the captured references stable and always current.
     */
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // Same trap, same remedy. Captured as a raw Boolean this would be read once when the gesture
    // coroutine starts and then never again, so toggling the setting mid-session would leave the
    // clamp stale even though the dismiss itself had correctly switched off.
    val currentPinAtCollapsed by rememberUpdatedState(pinAtCollapsed)

    Box(
        modifier = modifier
            .graphicsLayer {
                // background fades during about 10%-61% progress
                alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
            }
            .fillMaxSize(),
        content = background
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (collapsedSpan != null) {
                    Modifier.layout { measurable, constraints ->
                        val full = constraints.maxWidth
                        val narrow = state.isCollapsed
                        val width = if (narrow) collapsedSpan.second.roundToPx().coerceAtMost(full) else full
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(full, placeable.height) {
                            placeable.place(if (narrow) collapsedSpan.first.roundToPx() else 0, 0)
                        }
                    }
                } else Modifier
            )
            .offset {
                val y = (state.expandedBound - state.value)
                    .roundToPx()
                    .coerceAtLeast(0)
                IntOffset(x = 0, y = y)
            }
            .pointerInput(state) {
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        // Pinned: downward travel is capped at collapsedBound so the sheet does
                        // not slide away under the finger at all. Upward drags are untouched.
                        val delta = if (currentPinAtCollapsed) {
                            val room = (state.value - state.collapsedBound).coerceAtLeast(0.dp).toPx()
                            dragAmount.coerceAtMost(room)
                        } else {
                            dragAmount
                        }
                        state.dispatchRawDelta(delta)
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        state.snapTo(state.collapsedBound)
                    },
                    onDragEnd = {
                        val velocity = -velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        state.performFling(velocity, currentOnDismiss)
                    }
                )
            }
            .clip(
                RoundedCornerShape(
                    topStart = if (!state.isExpanded) 16.dp else 0.dp,
                    topEnd = if (!state.isExpanded) 16.dp else 0.dp
                )
            )
    ) {
        // Predictive back, because a plain BackHandler made this gesture invisible. You swiped
        // from the edge, nothing on screen moved, and the sheet blinked shut only once your finger
        // left the glass. That is the back gesture people make most often in a music app, and it
        // was the one place the app gave no sign it had understood you. Now the sheet follows the
        // drag, and springs back to where it was if you change your mind.
        val (backAnimations) = rememberPreference(BackAnimationsKey, defaultValue = true)

        // A sheet already on its way down leaves further back presses to whatever is under it (see
        // isCollapsing): pressing back quickly closes the queue and then the player, instead of
        // restarting the queue's fall from a standstill with every press. A Back that moves
        // another sheet (backMoves) waits on that one as well.
        val moves = backMoves
        val backOn = state.takesBack && (moves === state || moves.takesBack)
        if (!backAnimations) {
            // What it did before: no preview, the sheet shuts when the finger leaves the glass.
            if (backOn) {
                BackHandler(onBack = moves::collapseSoft)
            }
        } else PredictiveBackHandler(enabled = backOn) { progress ->
            val from = moves.value
            val to = backPreviewEnd(moves.collapsedBound, movesAnother = moves !== state)
            try {
                progress.collect { event ->
                    // The system hands over a linear 0..1. Used raw it feels dead to start with,
                    // since the first millimetres of the swipe move a full height sheet by almost
                    // nothing. Decelerating gives the sheet an immediate answer to the finger.
                    val eased = 1f - (1f - event.progress).pow(2)
                    moves.seekTo(lerp(from, to, eased))
                }
                moves.collapseSoft()
            } catch (cancelled: CancellationException) {
                // Not rethrown, following the platform's own sample: this is the gesture being
                // abandoned rather than a failure, and expandSoft launches on the sheet's scope
                // rather than this one, so the sheet still animates home after the cancellation.
                moves.expandSoft()
            }
        }

        // main
        if (!state.isCollapsed) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ((state.progress - 0.15f) * 4).coerceIn(0f, 1f)
                    },
                content = content
            )
        }

        // collapsed content
        if (!state.isExpanded) {
            // startY must be < state.collapsedBound
            val startY = with(density) { (MiniPlayerHeight + MinMiniPlayerHeight - 1.dp).toPx() }
            val colors = mutableListOf(collapsedBackgroundColor, Color.Transparent)
            // no visible gradient if no bottom content to hide it
            if (MiniPlayerHeight + MinMiniPlayerHeight >= state.collapsedBound) {
                colors[1] = collapsedBackgroundColor
            }
            Box(
                modifier = Modifier
                    .then(
                        if (collapsedSpan != null) {
                            // In a sheet already that narrow this is all of it. In one the width
                            // of the window, which it is while it opens, this keeps the place.
                            Modifier.layout { measurable, constraints ->
                                val full = constraints.maxWidth
                                val width = collapsedSpan.second.roundToPx().coerceAtMost(full)
                                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                                layout(full, placeable.height) {
                                    placeable.place(if (full > width) collapsedSpan.first.roundToPx() else 0, 0)
                                }
                            }
                        } else Modifier
                    )
                    .graphicsLayer {
                        alpha = 1f - (state.progress * 4).coerceAtMost(1f)
                    }
                    .dpadOverlay()
                    // Not a stop for the arrow keys itself. It spans the navigation bar as well
                    // as the mini player, so as one focusable box it could only be left upwards,
                    // and the buttons inside it could not be reached at all. The collapsed
                    // content offers its own way to open the sheet from the keys.
                    .focusProperties { canFocus = false }
                    .clickable(
                        onClick = state::expandSoft
                    )
                    .fillMaxWidth()
                    .height(state.collapsedBound)
                    .background(
                        Brush.verticalGradient(
                            colors = colors,
                            startY = startY,
                        )
                    ),
                content = collapsedContent
            )
        }
    }
}

/**
 * How far down a Back's preview takes the sheet it moves while the finger is still on the glass:
 * to its collapsed height, or just short of it when the Back is another sheet's ([BottomSheet]'s
 * backMoves). That is the queue's Back moving the player, and the queue and its handler are the
 * player's content, which goes at the collapsed height: the gesture would go with them, be called
 * off, and spring the player open again. Letting go closes it the rest of the way.
 */
internal fun backPreviewEnd(collapsedBound: Dp, movesAnother: Boolean): Dp =
    if (movesAnother) collapsedBound + BackPreviewShortBy else collapsedBound

/** See [backPreviewEnd]. Any height above the collapsed one keeps the content, and one dp does not show. */
internal val BackPreviewShortBy = 1.dp

/**
 * The player's sheet, for a screen under it that has a back of its own to give way with: while
 * the sheet is open, back is the sheet's (SettingsTwoPane's pages further in). Null where there
 * is no player sheet.
 */
val LocalPlayerSheet = staticCompositionLocalOf<BottomSheetState?> { null }

/**
 * The queue's sheet, for what lies under it in the player and has a Back of its own (the lyrics'
 * timing bar and picking): while the queue takes Back, Back is the queue's. Null where there is
 * no queue sheet.
 */
val LocalQueueSheet = staticCompositionLocalOf<BottomSheetState?> { null }

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
    private val currentAnchor: () -> Int,
) : DraggableState by draggableState {
    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        progress >= 1.0f
    }

    /**
     * On its way down, after a back press or a fling. It used to keep taking back presses then,
     * and each one snapped it to a standstill and started its soft fall again, so pressing back
     * quickly made the sheet slower, not faster (issue #31).
     */
    val isCollapsing by derivedStateOf {
        animatable.isRunning && animatable.targetValue == collapsedBound
    }

    /** Open, and not on its way down: a sheet that takes a Back (see isCollapsing). */
    val takesBack: Boolean
        get() = !isCollapsed && !isDismissed && !isCollapsing

    /**
     * Where the sheet was last sent: [expandedAnchor], [collapsedAnchor] or [dismissedAnchor]. Set
     * as it sets off, so a sheet on its way counts where it is going, and one held under the finger
     * or in a Back's preview still counts where it was.
     */
    val anchor: Int
        get() = currentAnchor()

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(collapsedAnchor)
        coroutineScope.launch {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(expandedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    private fun collapse() {
        collapse(BottomSheetAnimationSpec)
    }

    private fun expand() {
        expand(BottomSheetAnimationSpec)
    }

    fun collapseSoft() {
        collapse(BottomSheetSoftAnimationSpec)
    }

    fun expandSoft() {
        expand(BottomSheetSoftAnimationSpec)
    }

    fun dismiss() {
        onAnchorChanged(dismissedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.lowerBound!!)
        }
    }

    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    /**
     * Drives the sheet straight to a position, for a gesture that owns the frame.
     *
     * [snapTo] launches into the sheet's own scope, which is right for a fire and forget jump and
     * wrong here. Predictive back emits a position every frame and needs each one applied in
     * order, on the caller's coroutine, so that abandoning the gesture stops the updates with it
     * rather than leaving a queue of launched snaps to land afterwards.
     */
    suspend fun seekTo(target: Dp) {
        animatable.snapTo(target.coerceIn(dismissedBound, expandedBound))
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            // A slow release goes to whichever anchor the sheet is nearer, so the thresholds are
            // the midpoints between neighbouring anchors. They used to be half the gaps instead,
            // which is only the same thing for an anchor sitting at zero. The one between collapsed
            // and expanded fell short of halfway by the whole collapsed height, so a player let go
            // two thirds of the way down still sprang back open.
            val l0 = dismissedBound
            val l1 = (dismissedBound + collapsedBound) / 2
            val l2 = (collapsedBound + expandedBound) / 2
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }

                in l1..l2 -> collapse()
                in l2..l3 -> expand()
                else -> Unit
            }
        }
    }

    val preUpPostDownNestedScrollConnection
        get() = object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                }

                return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!isTopReached) {
                    isTopReached = consumed.y == 0f && available.y > 0
                }

                return if (isTopReached && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (isTopReached) {
                    val velocity = -available.y
                    performFling(velocity, null)

                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isTopReached = false
                return Velocity.Zero
            }
        }
}

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable = remember {
        Animatable(0.dp, Dp.VectorConverter)
    }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue = when (previousAnchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            dismissedAnchor -> dismissedBound
            else -> error("Unknown BottomSheet anchor")
        }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        coroutineScope.launch {
            animatable.animateTo(initialValue, NavigationBarAnimationSpec)
        }

        BottomSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            onAnchorChanged = { previousAnchor = it },
            currentAnchor = { previousAnchor },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound
        )
    }
}
