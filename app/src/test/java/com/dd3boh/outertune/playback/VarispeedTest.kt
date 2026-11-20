/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * #225, asked on Discord: "Pitch and speed share the same multiplier, with the option to decouple the pitch so you
 * can modify either individually." Tempo and Pitch had the two apart; Varispeed links them, as Metrolist does.
 */
class VarispeedTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = File(sources, path).readText()

    @Test
    fun `linked, the pitch is the speed, so nothing is stretched`() {
        assertEquals(PlaybackParameters(1.2f, 1.2f), Varispeed.parameters(1.2f, 3, linked = true))
        assertEquals(PlaybackParameters(0.5f, 0.5f), Varispeed.parameters(0.5f, -2, linked = true))
        assertEquals(PlaybackParameters.DEFAULT, Varispeed.parameters(1f, 0, linked = true))
    }

    @Test
    fun `apart, the pitch is its semitones whatever the speed, as before`() {
        assertEquals(PlaybackParameters(1.2f, 2f), Varispeed.parameters(1.2f, 12, linked = false))
        assertEquals(PlaybackParameters(1.2f, 1f), Varispeed.parameters(1.2f, 0, linked = false))
        assertEquals(2f.pow(-3 / 12f), Varispeed.parameters(0.9f, -3, linked = false).pitch, 1e-6f)
    }

    @Test
    fun `turned on, the song playing is linked at once`() {
        assertEquals(PlaybackParameters(1.3f, 1.3f), Varispeed.switched(PlaybackParameters(1.3f, 1f), linked = true))
        assertEquals(PlaybackParameters(0.8f, 0.8f), Varispeed.switched(PlaybackParameters(0.8f, 2f.pow(5 / 12f)), linked = true))
        assertEquals(PlaybackParameters.DEFAULT, Varispeed.switched(PlaybackParameters.DEFAULT, linked = true))
    }

    @Test
    fun `turned off, a linked pitch goes back to the song's key and a pitch set by hand stays`() {
        assertEquals(PlaybackParameters(1.3f, 1f), Varispeed.switched(PlaybackParameters(1.3f, 1.3f), linked = false))
        val byHand = PlaybackParameters(1.3f, 2f.pow(2 / 12f))
        assertEquals(byHand, Varispeed.switched(byHand, linked = false))
        assertEquals(PlaybackParameters.DEFAULT, Varispeed.switched(PlaybackParameters.DEFAULT, linked = false))
    }

    @Test
    fun `on for everyone in 0_13, his pick of 19 Nov 2026`() {
        assertTrue("val VARISPEED = true" in source("constants/Unreleased.kt"))
    }

    @Test
    fun `Tempo and Pitch shows the speed alone while linked, and sets both through Varispeed`() {
        val dialog = source("ui/menu/PlayerMenu.kt").substringAfter("fun PitchTempoDialog(").substringBefore("internal val TEMPO_STEPS")
        assertTrue("val linked = Unreleased.VARISPEED && varispeed" in dialog)
        assertTrue("rememberPreference(VarispeedKey, defaultValue = false)" in dialog)
        assertTrue("Varispeed.parameters(tempo, transposeValue, linked)" in dialog)
        assertTrue("if (!linked) ValueAdjuster(" in dialog)
        // A constructor call, not updatePlaybackParameters(), which goes through Varispeed.parameters.
        assertFalse("the dialog still builds its own parameters", Regex("""(?<![A-Za-z])PlaybackParameters\(""").containsMatchIn(dialog))
        // The speed row is drawn whatever the switch says.
        assertEquals(2, Regex("""ValueAdjuster\(""").findAll(dialog).count())
        assertTrue("values = TEMPO_STEPS," in dialog)
    }

    @Test
    fun `the switch sits after Skip silence, behind the flag`() {
        val frag = source("ui/screens/settings/fragments/PlayerFrag.kt").substringAfter("fun ColumnScope.AudioEffectsFrag()").substringBefore("fun LoudnessRepairEntry()")
        val silence = frag.indexOf("settingRow(R.string.skip_silence)")
        val flag = frag.indexOf("if (Unreleased.VARISPEED) {")
        val row = frag.indexOf("settingRow(R.string.varispeed)")
        assertTrue("$silence $flag $row", silence in 0 until flag && flag < row)
        assertTrue("rememberPreference(key = VarispeedKey, defaultValue = false)" in frag)
        assertTrue("description = stringResource(R.string.varispeed_description)" in frag)
    }

    @Test
    fun `the service links the song playing when the switch turns, behind the flag`() {
        val service = source("playback/MusicService.kt")
        val flag = service.indexOf("if (Unreleased.VARISPEED) {")
        assertTrue(flag >= 0)
        val block = service.substring(flag, service.indexOf("Varispeed.switched(", flag) + 200)
        assertTrue(".map { it[VarispeedKey] ?: false }" in block)
        assertTrue(".distinctUntilChanged()" in block)
        assertTrue("exoPlayer.playbackParameters = Varispeed.switched(exoPlayer.playbackParameters, linked)" in block)
    }

    @Test
    fun `in a Listen together room the speed plays in the song's own key, and the linked speed comes back after`() {
        // A guest with Varispeed on at x1.25 heard the host's songs four semitones high: the room set its speed and
        // kept the linked pitch.
        val session = source("together/TogetherSession.kt")
        assertTrue("private fun linked() = Unreleased.VARISPEED && service.varispeedOn" in session)
        val speed = session.substringAfter("private fun speed(player: Player, speed: Double) {").substringBefore("\n    }\n")
        assertTrue(speed, "val pitch = if (linked()) 1f else player.playbackParameters.pitch" in speed)
        assertTrue(speed, "PlaybackParameters(speed.toFloat(), pitch)" in speed)
        assertFalse("a restore still keeps the room's pitch", "speed(service.exoPlayer, speedBefore.toDouble())" in session)
        // Room stopped, own way, paused here, made the leader, the leader gone quiet, a guest's cast begun: six, besides its own line.
        assertEquals(6, Regex("""\bownSpeed\(\)""").findAll(session).count() - 1)
        val own = session.substringAfter("private fun ownSpeed() {").substringBefore("\n    }\n")
        assertTrue(own, "Varispeed.parameters(speedBefore, 0, linked = true)" in own)
        assertTrue(own, "if (!linked()) return speed(player, speedBefore.toDouble())" in own)
        assertTrue("varispeedOn = linked" in source("playback/MusicService.kt"))
    }

    @Test
    fun `Settings search finds it by the words people use`() {
        val index = source("ui/screens/settings/SettingsIndex.kt")
        assertTrue(
            "SettingEntry(Tour.ROUTE_PLAYER, R.string.varispeed, R.string.varispeed_description, words = listOf(R.string.search_words_varispeed), there = Unreleased.VARISPEED)," in index
        )
    }

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()
    private fun tag(xml: String, name: String) = Regex("""<string name="$name"[^>]*>""").find(xml)?.value
    private fun text(xml: String, name: String) = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(xml)?.groupValues?.get(1)

    @Test
    fun `its texts are held from translators, short, with their French beside them`() {
        for (name in listOf("varispeed", "varispeed_description", "search_words_varispeed")) {
            // Search words are open to translators whatever their feature (SettingsSearchTest); the rest is held.
            if (name != "search_words_varispeed") {
                assertTrue("$name is not held", "translatable=\"false\"" in (tag(english, name) ?: error("$name is not in the English file")))
            }
            assertNotNull("$name has no French", text(french, name))
            for (xml in listOf(english, french)) assertFalse("$name has a long dash", '—' in text(xml, name)!!)
        }
        for (xml in listOf(english, french)) {
            val description = text(xml, "varispeed_description")!!
            assertTrue(description, description.length <= 100 && description.count { it == '.' } == 1)
        }
    }
}
