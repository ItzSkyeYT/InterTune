/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Crossfade is held back: on in debug builds, off in previews and in a release, and off in every build
 * until the listener switches it on. It plays on AutoMix's stage, so this holds the places it is
 * wired in to the shape they were written in: the flag, the settings rows, the stage wanted with
 * it, the fade left out while it is on, and the controller planning a crossfade without Auto's
 * bands. This reads the source, as AutomixHeldBackTest does, because a flag is a constant of the
 * build and a test cannot turn it, and the controller listens to a player a test cannot build.
 */
class CrossfadeHeldBackTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    private fun source(path: String) = File(sources, path).readText()

    /** The text without its comments, so a sentence that names the flag is not taken for a read of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun lines(path: String) = code(source(path)).lines().map { it.trim() }

    private val service by lazy { code(source("playback/MusicService.kt")) }
    private val controller by lazy { code(source("playback/automix/AutomixController.kt")) }

    private fun body(text: String, header: String) = text.substringAfter(header).substringBefore("\n    }\n")

    @Test
    fun `Crossfade is on in debug builds only, as AutoMix is`() {
        assertTrue(Regex("""val CROSSFADE = BuildConfig\.DEBUG && BuildConfig\.BUILD_TYPE == "debug"\s*$""", RegexOption.MULTILINE).containsMatchIn(code(source("constants/Unreleased.kt"))))
    }

    @Test
    fun `the flag is read where its doc comment says and nowhere else`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.CROSSFADE\b""").containsMatchIn(code(it.readText())) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf(
                "playback/MusicService.kt",
                "ui/screens/settings/PlayerSettings.kt",
                "ui/screens/settings/fragments/PlayerFrag.kt",
                "ui/screens/settings/SettingsIndex.kt",
            ),
            readers,
        )
    }

    @Test
    fun `its rows are composed only under the flag, beside the fade, and start off`() {
        val settings = lines("ui/screens/settings/PlayerSettings.kt")
        val at = settings.indexOf("if (Unreleased.CROSSFADE) CrossfadeFrag()")
        assertTrue(at > 0)
        assertEquals("TransitionFadeFrag()", settings.subList(0, at).last { it.isNotEmpty() })
        val frag = code(source("ui/screens/settings/fragments/PlayerFrag.kt"))
        val crossfade = frag.substringAfter("fun ColumnScope.CrossfadeFrag()").substringBefore("\n}\n")
        assertTrue(crossfade, "rememberPreference(CrossfadeKey, defaultValue = false)" in crossfade)
        assertTrue(crossfade, "rememberPreference(CrossfadeSecondsKey, defaultValue = Crossfade.DEFAULT_SECONDS)" in crossfade)
        assertTrue(crossfade, "values = Crossfade.SECONDS_CHOICES," in crossfade)
        // Its length appears only once it is on, as the fade's does.
        assertTrue(crossfade, crossfade.indexOf("if (!enabled) return") in 1 until crossfade.indexOf("ListPreference("))
        // Each row is found by the search of Settings, behind the same flag.
        val index = lines("ui/screens/settings/SettingsIndex.kt")
        assertTrue("SettingEntry(Tour.ROUTE_PLAYER, R.string.crossfade, R.string.crossfade_description, there = Unreleased.CROSSFADE)," in index)
        assertTrue("SettingEntry(Tour.ROUTE_PLAYER, R.string.crossfade_length, under = R.string.crossfade, there = Unreleased.CROSSFADE)," in index)
    }

    @Test
    fun `the fade row is not used while Crossfade is on, as while AutoMix is`() {
        val fade = code(source("ui/screens/settings/fragments/PlayerFrag.kt"))
            .substringAfter("fun ColumnScope.TransitionFadeFrag()").substringBefore("\n}\n")
        assertTrue(fade, "val crossfadeOn = if (Unreleased.CROSSFADE) rememberPreference(CrossfadeKey, defaultValue = false).value else false" in fade)
        assertTrue(fade, "crossfadeOn -> R.string.transition_fade_crossfade_on" in fade)
        assertTrue(fade, "isEnabled = !held && !automixOn && !crossfadeOn," in fade)
        assertTrue(fade, "if (!enabled || held || automixOn || crossfadeOn) return" in fade)
    }

    @Test
    fun `the service leaves the fade out and wants the stage while Crossfade is on, never without the flags`() {
        val wanted = service.substringAfter("private fun crossfadeWanted(): Flow<Int?> = when {").substringBefore("}.distinctUntilChanged()")
        assertTrue(wanted, "!Unreleased.AUTOMIX || !Unreleased.CROSSFADE -> flowOf(null)" in wanted)
        assertTrue(wanted, "else -> dataStore.data.map { if (it[CrossfadeKey] == true) Crossfade.seconds(it[CrossfadeSecondsKey]) else null }" in wanted)
        assertTrue("{ fade, seconds, automixOn, crossfade -> (fade && !automixOn && crossfade == null) to seconds }" in service)
        // One collector hands both settings to the controller, inside the flag's check.
        val modes = service.substringAfter("if (Unreleased.AUTOMIX) {\n                if (Unreleased.AUTOMIX_EDITOR) {").substringBefore("if (BuildConfig.DEBUG) {")
        assertTrue(modes, "combine(automixWanted(), crossfadeWanted()) { on, crossfade -> on to crossfade }" in modes)
        assertTrue(modes, "automix?.setModes(on, crossfade, mixPlaylist = Unreleased.AUTOMIX_EDITOR && on)" in modes)
        assertEquals(2, Regex("""crossfadeWanted\(\)""").findAll(service.substringBefore("private fun crossfadeWanted()")).count())
        // The controller is on, and the stage wanted in the chain, while either is on.
        val modesFn = body(controller, "fun setModes(automix: Boolean, crossfadeSeconds: Int?, mixPlaylist: Boolean) {")
        assertTrue(modesFn, "enabled = automix || crossfadeSeconds != null" in modesFn)
        assertTrue("stage.wanted = value" in controller)
        assertTrue("var enabled = false\n        private set(value) {" in controller)
        // And offload stays off for it, as for AutoMix.
        assertTrue("(Unreleased.AUTOMIX && automix?.enabled == true)" in body(service, "private fun applyOffload()"))
    }

    @Test
    fun `the guards count Crossfade as on, and the rule reads the queue and the saved changes off the main thread`() {
        val snapshot = body(controller, "private fun snapshot(t: Transition): AutomixGuards.Snapshot {")
        assertTrue(snapshot, "settingOn = auto," in snapshot)
        assertTrue(snapshot, "crossfadeOn = crossfadeSeconds != null," in snapshot)
        val look = body(controller, "private fun lookAtQueue(newPair: Boolean) {")
        assertTrue(look, "withContext(Dispatchers.IO) { runCatching { Crossfade.savedSideBySide(songs, savedPairs()) } }" in look)
        assertEquals(1, Regex("""savedPairs\(\)""").findAll(controller).count())
        // Another start of a queue forgets what Auto mixed in the last one.
        assertTrue(look, "queueAutoMixed = false" in look)
        assertTrue(service, "queue = { queueBoard.getCurrentQueue()?.let { q -> AutomixController.QueueNow(q.id to q.runId) { q.songIds() } } }," in service)
        assertTrue(
            "savedPairs = { if (Unreleased.AUTOMIX_EDITOR) MixStores.transitions(this@MusicService).all().mapTo(HashSet()) { it.pair } else emptySet() }," in service,
        )
        // Only a real mix of Auto's turns Crossfade off for the queue, never a crossfade.
        assertTrue("if (mixing && !t.crossfaded) queueAutoMixed = true" in controller)
    }

    @Test
    fun `a crossfade is planned by Planner_crossfade, alone or where Auto stays gapless, and never on bands`() {
        val plan = body(controller, "private fun plan(t: Transition, held: HeldTail, ring: ShortArray, frames: Long) {")
        // No first plan only where the end was held for a later entry, which only the change on bands plays.
        assertTrue(plan, "val forEntry = t.entryFrames > 0L" in plan)
        assertTrue(plan, "val autoPlan = if (crossfadeFirst || forEntry) null else Planner.plan(planInput)" in plan)
        assertTrue(plan, "val crossfadeNow = crossfadeFirst || (crossfadeAfter && autoPlan?.isGapless == true)" in plan)
        assertTrue(plan, "val crossfade = if (crossfadeNow && seconds != null) Planner.crossfade(planInput, seconds) else null" in plan)
        assertTrue(plan, "val bands = lanes && !crossfaded" in plan)
        // Set before the plan is handed over, which the song change reads.
        assertTrue(plan, plan.indexOf("t.crossfaded = crossfaded") in 1 until plan.indexOf("stage.offer(held, plan, rendered)"))
        // Mix's line and the heard changes name Auto's changes only.
        assertTrue(plan, "t.crossfaded -> decide(t, null)" in plan)
        assertTrue("val said = choice?.takeIf { auto }" in controller)
        // A crossfade reads the opening alone: no copy, no saved change, no whole song.
        val read = body(controller, "private fun readHead(t: Transition, b: AutomixGuards.Song, way: Crossfade.Way) {")
        assertTrue(read, "val bands = lanes && way.auto" in read)
        assertTrue(read, "val grids = way.auto" in read)
        assertFalse(read, "if (lanes" in read)
    }

    @Test
    fun `its texts are held from translators, short, in English and in French, and use no em dash`() {
        val res = File("src/main/res")
        val english = File(res, "values/strings-ot.xml").readText()
        val french = File(res, "values-fr/strings-ot.xml").readText()
        val names = listOf("crossfade", "crossfade_description", "crossfade_length", "crossfade_note", "transition_fade_crossfade_on")
        for (name in names) {
            assertTrue("$name is not held from translators", "<string name=\"$name\" translatable=\"false\">" in english)
            for ((language, file) in listOf("English" to english, "French" to french)) {
                val text = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(file)?.groupValues?.get(1)
                assertTrue("$name has no $language", text != null)
                assertTrue("$name in $language has an em dash", '—' !in text!!)
                assertTrue("$name in $language runs past three sentences", Regex("[.!?](?=\\s|$)").findAll(text).count() <= 3)
            }
        }
        assertTrue(english, "<string name=\"crossfade\" translatable=\"false\">Crossfade</string>" in english)
        assertTrue(french, "<string name=\"crossfade\">Fondu enchaîné</string>" in french)
    }
}
