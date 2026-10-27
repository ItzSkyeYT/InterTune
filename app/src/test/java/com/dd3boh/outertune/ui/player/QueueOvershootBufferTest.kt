/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.BufferedSheetScrollConnection
import com.dd3boh.outertune.ui.component.collapsedAnchor
import com.dd3boh.outertune.ui.component.expandedAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.min

/**
 * The buffer at the top of a list, driven the way Compose drives a list's connection:
 * each finger movement offered to the connection, then to the list, then what the list left back
 * to the connection, and a fling at the end of every gesture. The sheet is a real BottomSheetState
 * at one pixel to the dp, open at 800 and down at 100, with a frame clock that plays its animations
 * to the end at once.
 */
class QueueOvershootBufferTest {
    private val open = 800.dp
    private val down = 100.dp
    private val buffer = 64f

    private val frames = object : MonotonicFrameClock {
        var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            now += 16_000_000L
            return onFrame(now)
        }
    }
    private val animatable = Animatable(open, Dp.VectorConverter).apply { updateBounds(down, open) }
    private var anchor = expandedAnchor
    private val sheet = BottomSheetState(
        // At once, where the app's own launches into the sheet's scope and lands within the frame.
        draggableState = DraggableState { delta -> runBlocking { animatable.snapTo(animatable.value - delta.dp) } },
        coroutineScope = CoroutineScope(Dispatchers.Unconfined + frames),
        animatable = animatable,
        onAnchorChanged = { anchor = it },
        collapsedBound = down,
        currentAnchor = { anchor },
    )
    private val connection = BufferedSheetScrollConnection(sheet, buffer)

    /** How far the list is scrolled from its top, and the furthest it can be, in pixels. */
    private var listOffset = 0f
    private var listEnd = 10_000f

    /** One finger movement, [dy] pixels down (up when negative), through the connection and the list. */
    private fun move(dy: Float, through: NestedScrollConnection = connection) {
        val pre = through.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput).y
        val left = dy - pre
        // Down scrolls the list towards its top, up towards its end, each as far as there is room.
        val listTook = if (left > 0f) min(left, listOffset) else maxOf(left, listOffset - listEnd)
        listOffset -= listTook
        through.onPostScroll(Offset(0f, listTook), Offset(0f, left - listTook), NestedScrollSource.UserInput)
    }

    /** A drag of [total] pixels in moves of 8, as a finger's arrive frame by frame. */
    private fun drag(total: Float, through: NestedScrollConnection = connection) {
        var left = total
        while (left != 0f) {
            val step = if (left > 0f) min(8f, left) else maxOf(-8f, left)
            move(step, through)
            left -= step
        }
    }

    /**
     * The finger leaves the glass at [vy] pixels a second, downwards when positive. Returns what the
     * connection took of the fling. What it leaves is the list's, and a list at its top that flings
     * on down sends its run into the top up as a side effect.
     */
    private fun fling(vy: Float, through: NestedScrollConnection = connection): Velocity = runBlocking {
        val taken = through.onPreFling(Velocity(0f, vy))
        val left = Velocity(0f, vy - taken.y)
        if (left.y > 0f && listOffset == 0f) {
            through.onPostScroll(Offset.Zero, Offset(0f, 120f), NestedScrollSource.SideEffect)
        }
        through.onPostFling(Velocity.Zero, left)
        taken
    }

    private fun assertOpen() {
        assertEquals(open, sheet.value)
        assertEquals(expandedAnchor, anchor)
    }

    private fun assertClosed() {
        assertEquals(down.value, sheet.value.value, 0.5f)
        assertEquals(collapsedAnchor, anchor)
    }

    @Test
    fun `a scroll up that runs past the top leaves the sheet until the buffer is used up`() {
        listOffset = 300f
        drag(300f + 56f)
        assertEquals(0f, listOffset)
        assertOpen()
        drag(8f) // the whole buffer, and not a pixel more
        assertOpen()
        drag(16f) // 16 beyond it
        assertEquals(open - 16.dp, sheet.value)
    }

    /**
     * What the queue did before, and what the player's sheet still does: the same scroll through
     * the plain connection starts to take the sheet down the moment the list is at its top, and the
     * fling closes it.
     */
    @Test
    fun `the plain connection, which the player keeps, still gives the overshoot to the sheet`() {
        val plain = sheet.preUpPostDownNestedScrollConnection
        listOffset = 300f
        drag(300f + 56f, plain)
        assertTrue(sheet.value < open)
        fling(6000f, plain)
        assertClosed()
    }

    @Test
    fun `a fling from such a scroll does not close the queue`() {
        listOffset = 300f
        drag(300f + 48f)
        assertEquals(Velocity.Zero, fling(6000f))
        assertOpen()
    }

    @Test
    fun `a fling from the moment the list reaches its top does not close it either`() {
        listOffset = 300f
        drag(300f)
        assertEquals(Velocity.Zero, fling(9000f))
        assertOpen()
    }

    @Test
    fun `past the buffer the sheet follows, and a fling closes it as before`() {
        listOffset = 300f
        drag(300f + buffer + 40f)
        assertEquals(open - 40.dp, sheet.value)
        assertEquals(Velocity(0f, 6000f), fling(6000f))
        assertClosed()
    }

    @Test
    fun `a pull down from the top moves the sheet at once, and a fling closes it`() {
        listOffset = 0f
        drag(16f)
        assertEquals(open - 16.dp, sheet.value)
        fling(6000f)
        assertClosed()
    }

    @Test
    fun `a slow pull down from the top let go past halfway closes it`() {
        listOffset = 0f
        drag(400f)
        assertEquals(open - 400.dp, sheet.value)
        fling(0f)
        assertClosed()
    }

    @Test
    fun `a list too short to scroll is a pull from the top every time`() {
        listOffset = 0f
        listEnd = 0f
        drag(-40f) // a finger up first: the list has nowhere to go
        drag(24f)
        assertEquals(open - 24.dp, sheet.value)
    }

    @Test
    fun `each gesture starts afresh`() {
        listOffset = 300f
        drag(300f + 32f)
        fling(0f)
        assertOpen()
        // The next starts with the list at its top: no buffer, whatever the last one did.
        drag(8f)
        assertEquals(open - 8.dp, sheet.value)
    }

    @Test
    fun `back up inside the buffer, the list moves again once the finger is back where it met the top`() {
        listOffset = 300f
        drag(300f + 40f)
        drag(-32f) // still 8 past the top
        assertEquals(0f, listOffset)
        assertOpen()
        drag(-24f) // 8 back to the top, then 16 into the list
        assertEquals(16f, listOffset)
        assertOpen()
    }

    @Test
    fun `the sheet comes back up with the finger, and is put home when let go`() {
        listOffset = 300f
        drag(300f + buffer + 48f)
        assertEquals(open - 48.dp, sheet.value)
        drag(-48f)
        assertEquals(open, sheet.value)
        drag(-buffer)
        assertEquals(0f, listOffset)
        drag(-24f)
        assertEquals(24f, listOffset)
        assertEquals(Velocity.Zero, fling(-3000f)) // the list's, flung on down the queue
        assertOpen()
    }

    @Test
    fun `back inside the buffer when let go, a fling leaves it open`() {
        listOffset = 300f
        drag(300f + buffer + 48f)
        drag(-56f) // the sheet home, 56 past the top
        fling(6000f)
        assertOpen()
    }

    @Test
    fun `the list's own fling never moves the sheet`() {
        listOffset = 0f
        connection.onPostScroll(Offset.Zero, Offset(0f, 500f), NestedScrollSource.SideEffect)
        connection.onPreScroll(Offset(0f, -500f), NestedScrollSource.SideEffect)
        assertOpen()
    }

    /**
     * The queue's own: a scroll that moved the list first never takes the sheet down at all, a pull
     * from the top has 48 of dead zone, and a fling counts once the sheet has come 64.
     */
    private val queue = BufferedSheetScrollConnection(sheet, Float.POSITIVE_INFINITY, 48f, 64f)

    @Test
    fun `in the queue, a scroll that runs past the top never moves it, however far or fast`() {
        listOffset = 300f
        drag(300f + 1500f, queue)
        assertEquals(0f, listOffset)
        assertOpen()
        assertEquals(Velocity.Zero, fling(12_000f, queue))
        assertOpen()
    }

    @Test
    fun `in the queue, a fresh pull from the top moves it past the dead zone, and a fling closes it`() {
        listOffset = 300f
        drag(300f + 400f, queue)
        fling(6000f, queue)
        assertOpen()
        drag(40f, queue) // inside the dead zone
        assertOpen()
        drag(8f + 64f, queue) // the dead zone's last 8, then 64
        assertEquals(open - 64.dp, sheet.value)
        fling(6000f, queue)
        assertClosed()
    }

    @Test
    fun `in the queue, a small pull on a list too short to scroll does nothing`() {
        listOffset = 0f
        listEnd = 0f
        drag(40f, queue)
        assertOpen()
        assertEquals(Velocity.Zero, fling(9000f, queue))
        assertOpen()
    }

    @Test
    fun `in the queue, a flick from a short pull past the dead zone puts it back open`() {
        listOffset = 0f
        drag(48f + 24f, queue)
        assertEquals(open - 24.dp, sheet.value)
        fling(9000f, queue)
        assertOpen()
    }

    @Test
    fun `in the queue, a slow pull from the top past halfway still closes it`() {
        listOffset = 0f
        drag(48f + 400f, queue)
        fling(0f, queue)
        assertClosed()
    }

    @Test
    fun `in the queue, back up after running far past the top, the list moves once the finger is back where it met the top`() {
        listOffset = 300f
        drag(300f + 600f, queue)
        drag(-600f, queue)
        assertEquals(0f, listOffset)
        assertOpen()
        drag(-24f, queue)
        assertEquals(24f, listOffset)
        assertOpen()
    }

    @Test
    fun `only the queue has the buffer`() {
        assertEquals(Dp.Infinity, QueueOvershootBuffer)
        assertEquals(48.dp, QueuePullDeadZone)
        assertEquals(64.dp, QueueFlingAfter)
        val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()
        // Each of the queue's three: its list of queues, its songs, and the pane round the queues on its side.
        assertEquals(3, Regex("""= rememberQueueSheetScroll\(queueState\)""").findAll(queue).count())
        assertFalse(queue.contains(".preUpPostDownNestedScrollConnection"))
        for (other in listOf("ui/player/Player.kt", "ui/component/Lyrics.kt", "ui/player/CastPicker.kt")) {
            val source = File("src/main/java/com/dd3boh/outertune/$other").readText()
            assertFalse(other, source.contains("BufferedSheetScrollConnection"))
        }
        val player = File("src/main/java/com/dd3boh/outertune/ui/player/Player.kt").readText()
        assertEquals(2, Regex("""nestedScroll\(state\.preUpPostDownNestedScrollConnection\)""").findAll(player).count())
        assertTrue(File("src/main/java/com/dd3boh/outertune/ui/component/BottomSheet.kt").readText()
            .contains("val preUpPostDownNestedScrollConnection"))
    }
}
