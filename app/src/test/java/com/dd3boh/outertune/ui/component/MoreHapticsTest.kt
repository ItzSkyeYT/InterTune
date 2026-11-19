/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * More haptics (Unreleased.HAPTICS_MORE, #176): the places Ti-Die listed and he picked tick on a
 * tap, and the seek bar rumbles faintly while dragged, a tick each fortieth of the bar. The rumble's
 * count is plain Kotlin and tested as such; where the ticks are wired is Compose and read from the
 * source, as DontPlayGreyTest does.
 */
class MoreHapticsTest {

    // ---- the seek bar's rumble ----

    /** The ticks a drag through [positions] of a song of [duration] gives, in order. */
    private fun ticks(positions: List<Long>, duration: Long = 200_000L, rumble: SeekRumble = SeekRumble()): Int =
        positions.count { rumble.tick(it, duration) }

    @Test
    fun `where the finger goes down is no tick, and a drag across the whole bar gives forty`() {
        assertEquals(0, ticks(listOf(0L)))
        // Every 100 ms of a 200 s song: 2,000 moves, one tick per fortieth of the bar.
        assertEquals(SeekRumble.STEPS, ticks((0L..200_000L step 100L).toList()))
        assertEquals(40, SeekRumble.STEPS)
    }

    @Test
    fun `a slow drag ticks as evenly as a quick one, never once for each move`() {
        val slow = ticks((0L..50_000L step 10L).toList())
        val quick = ticks((0L..50_000L step 1_000L).toList())
        assertEquals(10, slow)
        assertEquals(slow, quick)
    }

    @Test
    fun `a finger that trembles on the spot stays quiet`() {
        val wobble = (0 until 500).map { 100_000L + if (it % 2 == 0) 2_000L else -2_000L }
        assertEquals(0, ticks(wobble))
    }

    @Test
    fun `going back the other way ticks too, counted from the last tick`() {
        val rumble = SeekRumble()
        assertFalse(rumble.tick(100_000L, 200_000L))
        assertTrue(rumble.tick(105_000L, 200_000L))
        assertFalse(rumble.tick(101_000L, 200_000L))
        assertTrue(rumble.tick(100_000L, 200_000L))
    }

    @Test
    fun `a new drag counts from where it goes down, and a song with no length never ticks`() {
        val rumble = SeekRumble()
        rumble.tick(0L, 200_000L)
        rumble.reset()
        // Far from the last drag: the first move of the new one sets where it counts from.
        assertFalse(rumble.tick(150_000L, 200_000L))
        assertEquals(0, ticks((0L..10_000L step 100L).toList(), duration = 0L))
        assertEquals(0, ticks((0L..10_000L step 100L).toList(), duration = -1L))
    }

    @Test
    fun `a position past either end counts as the end`() {
        val rumble = SeekRumble()
        rumble.tick(-5_000L, 200_000L)
        assertFalse(rumble.tick(0L, 200_000L))
        rumble.reset()
        rumble.tick(200_000L, 200_000L)
        assertFalse(rumble.tick(250_000L, 200_000L))
    }

    // ---- where the ticks are wired ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    /** From [header] to the next line that opens with @Composable or a top-level fun. */
    private fun declaration(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("no $header", start >= 0)
        val end = Regex("""\n(@Composable|fun |private fun |internal fun )""").find(text, start + header.length)?.range?.first ?: text.length
        return text.substring(start, end)
    }

    private val player get() = source("ui/player/Player.kt")

    @Test
    fun `the flag is held back, and every tick of a tap asks it`() {
        val flags = source("constants/Unreleased.kt")
        assertTrue("val HAPTICS_MORE = BuildConfig.PREVIEW_FEATURES" in flags)
        val helper = source("ui/component/MoreHaptics.kt")
        assertTrue("if (Unreleased.HAPTICS_MORE) performHapticFeedback(HapticFeedbackType.ContextClick)" in helper)
    }

    @Test
    fun `both kinds of player button tick on a tap, so every pinned button does, the queue's included`() {
        for (header in listOf("private fun PlayerCircleButton(", "private fun PlayerActionSegment(")) {
            val button = declaration(player, header)
            assertTrue("$header has no tick", "haptic.tapTick()\n                    onClick()" in button)
            assertFalse("$header still clicks without it", ".clickable(onClick = onClick)" in button)
        }
        // The queue's button ticked by itself before: with the flag, once, from the button.
        assertTrue("if (!Unreleased.HAPTICS_MORE) haptic.performHapticFeedback(HapticFeedbackType.ContextClick)" in player)
    }

    @Test
    fun `the title and each artist tick before they open their page`() {
        assertTrue(".clickable(enabled = mediaMetadata.album != null) {\n                                        haptic.tapTick()" in player)
        assertTrue(".clickable(enabled = artist.id != null) {\n                                            haptic.tapTick()" in player)
    }

    @Test
    fun `the seek bar rumbles while dragged and starts afresh when the finger lifts`() {
        assertTrue("if (Unreleased.HAPTICS_MORE && seekRumble.tick(it, shownDuration)) {" in player)
        assertTrue("onSlideDone = {\n                            seekRumble.reset()" in player)
        // The tick for each move stays out.
        assertEquals(1, Regex("""SegmentFrequentTick""").findAll(player.substringAfter("onSlide = {").substringBefore("onKey = ")).count())
    }

    @Test
    fun `every entry of a three-dot menu ticks, and the lyrics' buttons do`() {
        val entry = declaration(source("ui/menu/GridMenu.kt"), "private fun GridMenuEntry(")
        assertTrue("onClick = {\n                        haptic.tapTick()\n                        onClick()\n                    }," in entry)
        val lyrics = source("ui/component/Lyrics.kt")
        assertTrue("haptic.tapTick()\n                        onShowLyricsChange(false)" in lyrics)
        assertTrue("haptic.tapTick()\n                        menuState.show {\n                            LyricsMenu(" in lyrics)
        // And while lines are picked for a picture, the bar in their place: its cross and Share.
        val pickBar = declaration(source("ui/component/LyricsCard.kt"), "fun LyricsPickBar(")
        assertTrue("IconButton(onClick = {\n                haptic.tapTick()\n                onCancel()" in pickBar)
        assertTrue("onClick = {\n                    haptic.tapTick()\n                    onShare()" in pickBar)
    }

    @Test
    fun `the buttons inside the menus tick too, the heart in their header and the Advanced dialog's steps`() {
        // The heart beside a song, an album, an artist or a playlist at the top of its menu, YouTube's twins included.
        for (menu in listOf("SongMenu", "YouTubeSongMenu", "AlbumMenu", "YouTubeAlbumMenu", "ArtistMenu", "YouTubeArtistMenu", "PlaylistMenu", "YouTubePlaylistMenu")) {
            // The header's start: the heart, behind an if in the playlist menus.
            val header = source("ui/menu/$menu.kt").substringAfter("trailingContent = {").take(400)
            assertTrue("$menu's header button has no tick", "val haptic = LocalHapticFeedback.current" in header && "haptic.tapTick()" in header)
        }
        // Tempo and pitch, a step at a time, in the player menu's Advanced dialog.
        val adjuster = source("ui/menu/PlayerMenu.kt").substringAfter("fun <T : Comparable<T>> ValueAdjuster(")
        assertEquals(2, Regex("""haptic\.tapTick\(\)\s+stepValue\(values, currentValue, up = (true|false)\)""").findAll(adjuster).count())
        // The lyrics search's expand.
        assertTrue("haptic.tapTick()\n                            expandedItemIndex" in source("ui/menu/LyricsMenu.kt"))
    }
}
