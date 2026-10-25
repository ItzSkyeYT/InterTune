/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AutoMix is held back: on in debug and preview builds, off in a release, and off in every build
 * until the listener switches it on. With the flag or the setting off, playback has to be exactly
 * what it was, so this holds the places the flag is read to the shape they were written in: the
 * row composed only under the flag, the key read as off when absent, the fade row unchanged
 * unless AutoMix is on.
 *
 * This reads the source, as ShortSetupHeldBackTest does, because a flag is a constant of the build
 * and a test cannot turn it.
 */
class AutomixHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()

    /** The text without its comments, so a sentence that names the flag is not taken for a read of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun lines(path: String) = code(source(path)).lines().map { it.trim() }

    @Test
    fun `AutoMix is on in debug and preview builds only`() {
        assertTrue(Regex("""val AUTOMIX = BuildConfig\.DEBUG\s*$""", RegexOption.MULTILINE).containsMatchIn(code(source("constants/Unreleased.kt"))))
    }

    @Test
    fun `the row is composed only under the flag, and starts off`() {
        assertTrue("if (Unreleased.AUTOMIX) AutomixFrag()" in lines("ui/screens/settings/PlayerSettings.kt"))
        val frag = code(source("ui/screens/settings/fragments/PlayerFrag.kt"))
        val automix = frag.substringAfter("fun ColumnScope.AutomixFrag()").substringBefore("\n}\n")
        assertTrue(automix, "rememberPreference(AutomixKey, defaultValue = false)" in automix)
    }

    @Test
    fun `the fade row is what it was unless AutoMix is on`() {
        val fade = code(source("ui/screens/settings/fragments/PlayerFrag.kt"))
            .substringAfter("fun ColumnScope.TransitionFadeFrag()").substringBefore("\n}\n")
        assertTrue(fade, "val automixOn = if (Unreleased.AUTOMIX) rememberPreference(AutomixKey, defaultValue = false).value else false" in fade)
        // Battery use's Saver holds the same row (BatteryUse), so the two conditions sit side by side.
        assertTrue(fade, "isEnabled = !held && !automixOn," in fade)
        assertTrue(fade, "if (!enabled || automixOn || held) return" in fade)
    }

    @Test
    fun `its texts are short, in English and in French, and use no em dash`() {
        val res = File("src/main/res")
        val english = File(res, "values/strings-ot.xml").readText()
        val french = File(res, "values-fr/strings-ot.xml").readText()
        for (name in listOf("automix", "automix_description", "automix_explain", "transition_fade_automix_on")) {
            for ((language, file) in listOf("English" to english, "French" to french)) {
                val text = Regex("""<string name="$name">(.*?)</string>""").find(file)?.groupValues?.get(1)
                assertTrue("$name has no $language", text != null)
                assertTrue("$name in $language has an em dash", '—' !in text!!)
                assertTrue("$name in $language runs past three sentences", Regex("[.!?](?=\\s|$)").findAll(text).count() <= 3)
            }
        }
    }

    @Test
    fun `the flag is read where its doc comment says and nowhere else`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { "Unreleased.AUTOMIX" in code(it.readText()) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf(
                "ui/screens/settings/PlayerSettings.kt",
                "ui/screens/settings/fragments/PlayerFrag.kt",
                "ui/screens/settings/fragments/DeveloperFrag.kt",
                "ui/screens/settings/SettingsIndex.kt",
                "playback/MusicService.kt",
            ),
            readers,
        )
    }

    private val service by lazy { lines("playback/MusicService.kt") }

    @Test
    fun `without the flag the service makes no stage, and the chain and the sink are the ones they were`() {
        assertTrue("private val automixStage: AutomixAudioProcessor? = if (Unreleased.AUTOMIX) AutomixAudioProcessor() else null" in service)
        assertTrue("return if (Unreleased.AUTOMIX && stage != null) AutomixProcessorChain(chain, stage) else chain" in service)
        assertTrue("return if (Unreleased.AUTOMIX && stage != null) AutomixAudioSink(sink, stage) else sink" in service)
        // Both ways the renderers are built, the chain and the sink each through the same door.
        val factory = code(source("playback/MusicService.kt")).substringAfter("private fun createRenderersFactory(").substringBefore("\n// Misc")
        assertEquals(2, Regex("""\.setAudioProcessorChain\(\s*withAutomix\(""").findAll(factory).count())
        assertEquals(2, Regex("""\.let \{ withAutomix\(it\) \}\s*\.let \{ LevelTapAudioSink\(it, levelTap\) \}""").findAll(factory).count())
    }

    @Test
    fun `the controller is made only under the flag, and the setting is off until it is switched on`() {
        val text = code(source("playback/MusicService.kt"))
        val made = text.substringBefore("automix = AutomixController(").substringAfterLast("if (Unreleased.AUTOMIX) {")
        assertTrue("the controller is made outside the flag's check", made.lines().size < 4)
        assertTrue("dataStore.data.map { it[AutomixKey] ?: false }.distinctUntilChanged()" in service)
        assertTrue(".map { Unreleased.AUTOMIX && (it[AutomixKey] ?: false) }" in service)
    }

    @Test
    fun `the volume is today's unless the stage is in the chain`() {
        val text = code(source("playback/MusicService.kt"))
        assertTrue(text.contains(Regex("""exoPlayer\.volume = AutomixVolume\.playerVolume\(\s*normalize, volume, sleepTimer\.fadeFactor\.value, proximityVolume\.factor\.value,\s*transitionFade\.factor\.value, stageNormalises\?\.value == true,\s*\)""")))
        assertTrue("normalizeFactor.value = Normalization.factor(format?.loudnessDb, isLocal, normalizeAudio)" in service)
    }

    @Test
    fun `offload stays off while AutoMix is on, as for the fade`() {
        val offload = code(source("playback/MusicService.kt")).substringAfter("private fun applyOffload()").substringBefore("\n    }")
        assertTrue(offload, "(Unreleased.AUTOMIX && automix?.enabled == true)" in offload)
    }
}
