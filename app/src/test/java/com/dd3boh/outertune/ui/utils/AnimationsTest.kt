/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Animations switch of Look and feel (his yes of 4 Nov 2026, from a tester's wish in the 0.13
 * poll, for older and smaller phones). One rule decides that animations are off, from Android's
 * own Remove animations and the switch, and every composition of the app goes by it, and so does
 * everything that moves by a clock of its own.
 */
class AnimationsTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun read(path: String) = File(sources, path).readText()

    /** Every source file of the app, by its path under the package. */
    private val main: Map<String, String> = sources.walkTopDown()
        .filter { it.extension == "kt" }
        .associate { it.relativeTo(sources).path to it.readText() }

    @Test
    fun `one rule, off with Android's Remove animations or with the switch off, Android's own pace otherwise`() {
        assertEquals(1f, Animations.pace(system = 1f, switchOn = true), 0f)
        assertEquals(0f, Animations.pace(system = 0f, switchOn = true), 0f)
        assertEquals(0f, Animations.pace(system = 1f, switchOn = false), 0f)
        assertEquals(0f, Animations.pace(system = 0f, switchOn = false), 0f)
        // Android's slower and faster paces stay as they are while the switch is on.
        assertEquals(0.5f, Animations.pace(system = 0.5f, switchOn = true), 0f)
        assertEquals(5f, Animations.pace(system = 5f, switchOn = true), 0f)
    }

    @Test
    fun `the switch is on until it is turned off, wherever it is read`() {
        assertTrue(Animations.SWITCH_DEFAULT)
        assertTrue(Animations.Pace().switchOn)
        val reads = main.flatMap { (path, text) ->
            text.lines().filter { Regex("""\bAnimationsKey\s*[\],]""").containsMatchIn(it) }.map { "$path: ${it.trim()}" }
        }
        assertTrue(reads.toString(), reads.size >= 3)
        assertTrue(reads.filter { "SWITCH_DEFAULT" !in it }.toString(), reads.all { "SWITCH_DEFAULT" in it })
    }

    @Test
    fun `the pace is read as snapshot state, so a flip reaches what waits on it`() {
        // InfiniteTransition waits with snapshotFlow for the pace to come back above 0, and the
        // screens that ask Animations.off compose again: both see only a change they read as state.
        val pace = Animations.Pace()
        val reads = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { reads += it }) { pace.scaleFactor }
        assertEquals(2, reads.size)
    }

    /** A frame each time one is asked for, 16 ms after the last, counted. */
    private class Frames : MonotonicFrameClock {
        var count = 0
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            count++
            now += 16_000_000L
            return onFrame(now)
        }
    }

    /** The frames that an animation of a second takes, with [pace] where a composition has it. */
    private fun framesOfASecond(pace: Animations.Pace): Int {
        val frames = Frames()
        runBlocking(frames + pace) { Animatable(0f).animateTo(1f, tween(durationMillis = 1000)) }
        return frames.count
    }

    @Test
    fun `in the app's pace an animation takes its time, and with animations off it is over at its first frame`() {
        val moving = framesOfASecond(Animations.Pace())
        assertTrue("$moving frames", moving > 50)
        val switchedOff = framesOfASecond(Animations.Pace().apply { switchOn = false })
        assertTrue("$switchedOff frames", switchedOff <= 2)
        val offInAndroid = framesOfASecond(Animations.Pace().apply { system = 0f })
        assertTrue("$offInAndroid frames", offInAndroid <= 2)
    }

    @Test
    fun `Android's setting is read in one place`() {
        assertEquals(setOf("ui/utils/Animations.kt"), main.filterValues { "ANIMATOR_DURATION_SCALE" in it }.keys)
    }

    @Test
    fun `every window's composition is given the app's pace`() {
        // The recomposer Compose makes for a window by itself follows Android's setting alone.
        val roots = main.flatMap { (path, text) ->
            Regex("""\bsetContent\s*(\((?:[^()]|\([^()]*\))*\))?\s*\{""").findAll(text).map { "$path: ${it.value}" }.toList()
        }
        assertTrue(roots.toString(), roots.size >= 2)
        assertTrue(roots.toString(), roots.all { "setContent(parent = Animations.recomposer(this))" in it })
    }

    @Test
    fun `what moves by a clock of its own asks the same rule`() {
        // Living blur's frames, the frames of the (i) pictures, and Coil's fade of the covers,
        // which keeps the time on the wall: none of them goes by the pace a composition is given.
        assertTrue(read("ui/player/LivingBackground.kt").contains("|| Animations.off"))
        assertTrue(read("ui/component/ExplainPictures.kt").contains("val pace = Animations.current.scaleFactor"))
        val app = read("App.kt")
        assertFalse(app.contains(".crossfade("))
        assertEquals(2, Regex("""\.transitionFactory\(CoverFade\(\)\)""").findAll(app).count())
        assertTrue(read("ui/utils/Animations.kt").contains("if (Animations.off) Transition.Factory.NONE else fade"))
    }

    @Test
    fun `while Android's setting has animations off, the switch shows off, cannot be moved, and says why`() {
        val frag = read("ui/screens/settings/fragments/ThemeFrag.kt")
        assertTrue(frag.contains("rememberPreference(AnimationsKey, defaultValue = Animations.SWITCH_DEFAULT)"))
        assertTrue(frag.contains("val heldOff = Animations.offInSystem"))
        assertTrue(frag.contains("checked = animations && !heldOff"))
        assertTrue(frag.contains("isEnabled = !heldOff"))
        assertTrue(frag.contains("if (heldOff) InfoLabel(stringResource(R.string.animations_held_note))"))
    }

    private val held = listOf("animations_title", "animations_description", "animations_held_note", "living_blur_still_switch")

    private fun text(folder: String, name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(File("src/main/res/$folder/strings-ot.xml").readText())?.groupValues?.get(1)

    @Test
    fun `its words are held from translators, have their French, and are short and plain`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        for (name in held) {
            assertTrue("$name is not held from translators", "<string name=\"$name\" translatable=\"false\">" in english)
            for (folder in listOf("values", "values-fr")) {
                val words = text(folder, name)
                assertTrue("$folder $name is missing", words != null)
                assertFalse("$folder $name has a dash", '—' in words!! || '–' in words)
                assertTrue("$folder $name says too much", Regex("[.!?](?=\\s|$)").findAll(words).count() <= 2)
            }
        }
    }
}
