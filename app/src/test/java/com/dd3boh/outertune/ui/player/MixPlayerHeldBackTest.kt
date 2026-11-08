/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player while a change plays as a mix is AutoMix's, and held back with it: on in the debug
 * app only (Unreleased.AUTOMIX). Without the flag the player is exactly what it was, which a test
 * cannot see by turning a constant of the build, so this reads the source, as AutomixHeldBackTest
 * does: the one door the screen comes in by, and every place that draws differently going
 * through it.
 */
class MixPlayerHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a call is not taken for one. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val player by lazy { code("ui/player/Player.kt") }
    private val mixPlayer by lazy { code("ui/player/MixPlayer.kt") }
    private val thumbnail by lazy { code("ui/player/Thumbnail.kt") }
    private val controller by lazy { code("playback/automix/AutomixController.kt") }

    @Test
    fun `without the flag there is no screen for a mix, and the service has nothing to give one`() {
        val remember = mixPlayer.substringAfter("fun rememberMixScreen(").substringBefore("\n}\n")
        assertEquals("if (!Unreleased.AUTOMIX) return null", remember.lines().drop(1).first { it.isNotBlank() }.trim())
        // The flow is the controller's, which is made under the flag alone (AutomixHeldBackTest).
        assertTrue("val mixOnScreen get() = automix?.onScreen" in code("playback/MusicService.kt"))
    }

    @Test
    fun `the player gets its mix from that door alone, and with none draws what it drew`() {
        assertEquals(1, Regex("""rememberMixScreen\(""").findAll(player).count())
        assertTrue("val mix = rememberMixScreen(playerConnection, lyricsShown) { !state.isCollapsed && !state.isDismissed }" in player)
        // The colours: the player's content as it is, with no theme of its own, without a mix.
        val colours = mixPlayer.substringAfter("fun MixColours(").substringBefore("\n}\n")
        assertTrue(colours, "if (mix == null) return content()" in colours)
        // The background drawn over the usual one only with a mix, and only while one is on screen.
        assertTrue("if (mix != null) MixBackdrop(" in player)
        val backdrop = mixPlayer.substringAfter("internal fun MixBackdrop(").substringBefore("\n}\n")
        assertTrue(backdrop, "val change = mix.target ?: return" in backdrop)
        assertTrue(backdrop, "if (!mix.active) return" in backdrop)
        // The words' fade, a layer only with a mix: the title and the seek bar.
        assertEquals(2, Regex("""if \(mix != null\) Modifier\.graphicsLayer \{ alpha = mix\.wordsAlpha \} else Modifier""").findAll(player).count())
        // Living blur's second cover only while a change is on screen.
        assertTrue("val mixShown = mix?.takeIf { it.active }?.target" in player)
        assertTrue("blendCover = mixShown?.incoming?.getThumbnailModel(100, 100)," in player)
        // The words and the buttons on the outgoing song only while the screen says so.
        assertTrue("val outgoingShown = mix?.takeIf { it.showsOutgoing }?.target?.outgoing" in player)
        // The strip jumps only into a mix.
        assertEquals(2, Regex("""jump = mix\?\.bringsIn\(mediaMetadata\?\.id\) == true,""").findAll(player).count())
        assertTrue("if (expanded && !jump) gridState.animateScrollToItem(currentIndex) else gridState.scrollToItem(currentIndex)" in player)
    }

    /** Each call of [name] in [text], from its name to its closing bracket. */
    private fun calls(text: String, name: String): List<String> = Regex("""\b$name\(""").findAll(text).map { found ->
        var depth = 0
        var end = found.range.last
        do {
            when (text[end]) {
                '(' -> depth++
                ')' -> depth--
            }
            end++
        } while (depth > 0)
        text.substring(found.range.first, end)
    }.toList()

    @Test
    fun `every cover of the player is told of the mix, and draws both covers only on the incoming song's`() {
        val covers = calls(player, "Thumbnail")
        assertEquals("the player's covers", 4, covers.size)
        for (call in covers) assertTrue(call, "mix = mix," in call)
        assertTrue(thumbnail, "val mixed = mix?.takeIf { ownsError && it.active && it.target?.incoming?.id == mediaMetadata?.id }" in thumbnail)
        // Today's rounded box, unless both covers are in it, each in its own shape.
        assertTrue(".then(if (outgoing == null) Modifier.clip(RoundedCornerShape(ThumbnailCornerRadius * 2)) else Modifier)" in thumbnail)
        assertTrue("Box(Modifier.fillMaxSize().then(if (mixed != null && outgoing != null) Modifier.mixCover(mixed, outgoing = false, morphs) else Modifier)) {" in thumbnail)
    }

    @Test
    fun `the controller hands every change of the blend to the screen's rules`() {
        fun body(start: String) = controller.substringAfter(start).substringBefore("\n    }\n")
        // A song starting, or a jump, is looked at before anything that waits for AutoMix to be on:
        // a blend plays out after AutoMix is switched off, and the screen with it.
        val transition = body("override fun onMediaItemTransition(")
        assertTrue(transition.indexOf("screen.songStarted(mediaItem?.mediaId)") in 0 until transition.indexOf("if (!enabled)"))
        val jump = body("override fun onPositionDiscontinuity(")
        assertTrue(jump.indexOf("screen.jumped(blendPlaying = stage.state == HeldTail.State.MIX)") in 0 until jump.indexOf("if (!enabled)"))
        assertTrue("if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) screen.hide(null, \"playback stopped\")" in controller)
        // Shown as the blend starts, never for a crossfade.
        assertTrue("showMix(t.takeIf { mixing && !it.crossfaded })" in body("override fun songChanged("))
        // Taken off at once when given up, by a guard, the lock check, or the stage.
        assertTrue("screen.hide(t.id, why)" in body("private fun abort("))
        assertTrue("screen.hide(t.id, \"the incoming song does not line up with its opening\")" in controller)
        assertTrue("screen.stageSaid(id, text, resumable = screenHeld?.resumable == true)" in body("override fun note("))
        // A drop is looked at before the change's own end: a blend let go by a jump may be taken up again.
        val dropped = controller.substringAfter("override fun dropped(").substringBefore("\n    }\n")
        assertTrue(dropped.indexOf("if (screen.dropped(t.id)) watchResume(t.id)") in 0 until dropped.indexOf("if (t.finished) return@postFor"))
    }

    @Test
    fun `its words have no em dash`() {
        for (path in listOf("ui/player/MixPlayer.kt", "ui/player/MixLook.kt", "playback/automix/MixOnScreen.kt")) {
            assertFalse(path, '\u2014' in File(sources, path).readText())
        }
    }
}
