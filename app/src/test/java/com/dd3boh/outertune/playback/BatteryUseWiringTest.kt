/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Battery use is a rule applied where each setting is used (BatteryUse), so it only does anything
 * where the code that uses a setting asks the rule. A JVM test cannot play, sync or draw, so the
 * places are read from the source, comments taken out: each one asks, none of them is left on the
 * old fixed value, and nothing a mode does writes a setting.
 */
class BatteryUseWiringTest {

    private val root = File("src/main/java/com/dd3boh/outertune")
    private fun source(path: String) = stripComments(File(root, path).readText())

    private val service = source("playback/MusicService.kt")

    /** The body of the function whose head is [head], to its closing brace. */
    private fun body(text: String, head: String): String {
        val from = text.indexOf(head)
        assertTrue("no $head", from >= 0)
        var depth = 0
        var i = text.indexOf('{', from)
        val start = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        error("$head never closes")
    }

    @Test
    fun `the player asks the rule for every sound effect it applies`() {
        assertTrue("spatial audio", "BatteryUse.spatialAudio(" in service)
        assertTrue("the equalizer", "BatteryUse.equalizer(" in service)
        assertTrue("the fade between songs", "BatteryUse.fade(" in service)
        assertTrue("the volume past 100%", "BatteryUse.volume(" in service)
        assertTrue("Quieter as you walk away", "BatteryUse.proximity(" in service)
        // Each of them reads the mode from the same settings it reads its own value from, so the
        // two can never disagree for a moment.
        assertEquals(5, Regex("""BatteryUse\.modeOf\((it|prefs)\[BatteryModeKey]\)""").findAll(service).count())
    }

    @Test
    fun `offload is decided in one place, by the rule, with everything that needs the app's own path`() {
        val apply = body(service, "private fun applyOffload()")
        assertTrue("BatteryUse.offload(batteryMode, offloadSwitch, needsAppPath)" in apply)
        for (needs in listOf(
            "spatialUpmixProcessor.enabled", "binauralProcessor.enabled", "transitionFade.enabled",
            "!equalizerProcessor.settings.isFlat", "exoPlayer.skipSilenceEnabled",
            "exoPlayer.playbackParameters != PlaybackParameters.DEFAULT", "sleepTimer.isActive",
        )) {
            assertTrue("$needs is not among what keeps a song on the app's path", needs in apply)
        }
        assertFalse("no blocking read of the switch on the main thread", "dataStore" in apply)
        // And asked again whenever one of them changes: the sleep timer and another speed too.
        assertTrue("snapshotFlow { sleepTimer.isActive }.distinctUntilChanged()" in service)
        assertTrue("events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)" in service)
        assertTrue("the player starts by the same rule", "setOffloadEnabled(BatteryUse.offload(" in service)
        assertEquals("setOffloadEnabled is called in two places only", 2, Regex("""setOffloadEnabled\(""").findAll(service).count())
    }

    @Test
    fun `the developer's Enable offload is still read, so a switch turned on by hand keeps its offload`() {
        assertTrue("offloadSwitch = it.first" in service || Regex("""offloadSwitch = .*AudioOffloadKey""").containsMatchIn(service))
        assertTrue("AudioOffloadKey" in source("ui/screens/settings/fragments/DeveloperFrag.kt"))
    }

    @Test
    fun `the stream and the radios' taste follow the mode`() {
        assertTrue(Regex("""BatteryUse\.quality\(quality, metered, batteryMode\)""").containsMatchIn(body(service, "private fun audioQualityNow()")))
        assertTrue("BatteryUse.tasteKeptMs(batteryMode)" in body(service, "private fun tasteFor("))
        assertFalse("TASTE_TTL_MS" in service)
    }

    @Test
    fun `an automatic library sync waits as long as the mode says, and Saver waits for Wi-Fi`() {
        val sync = source("utils/SyncUtils.kt")
        assertTrue("BatteryUse.syncEverySeconds(" in sync)
        assertTrue("BatteryUse.syncAllowed(" in sync)
        assertFalse("no sync left on the old fixed half hour", Regex("""elapsed < SYNC_CD""").containsMatchIn(sync))
        // Sync now and a pull are someone asking: they are never held.
        val auto = body(sync, "private suspend fun autoSync(")
        assertTrue(auto.indexOf("if (!bypassCd)") in 0 until auto.indexOf("autoSyncHeld("))
    }

    @Test
    fun `downloads, the song cache and the player's background follow the mode`() {
        val downloads = source("playback/DownloadUtil.kt")
        assertTrue(Regex("""private fun wifiOnlyNow\(\): Boolean = BatteryUse\.downloadsOnWifiOnly\(""").containsMatchIn(downloads))
        assertTrue("the requirement the manager starts with", "requirements = downloadRequirements(wifiOnlyNow())" in downloads)
        assertTrue("the note about waiting for Wi-Fi", "if (wifiOnlyNow() && connectivityManager.isActiveNetworkMetered)" in downloads)
        assertFalse("no other read of the switch alone", "dataStore.get(DownloadOnWifiOnlyKey, false) &&" in downloads)
        assertTrue("BatteryUse.songCacheMb(" in source("di/AppModule.kt"))
        assertTrue("BatteryUse.playerBackground(" in source("ui/player/Player.kt"))
        assertTrue("BatteryUse.playerBackground(" in source("ui/component/LyricsCard.kt"))
        assertTrue("BatteryUse.bars(" in source("ui/component/PlayingIndicator.kt"))
        assertTrue("BatteryUse.volume(" in source("ui/menu/PlayerMenu.kt"))
    }

    @Test
    fun `the row is offered only where the build has the choice, under Audio quality`() {
        val screen = source("ui/screens/settings/PlayerSettings.kt")
        val row = screen.indexOf("if (Unreleased.BATTERY_MODES) BatteryUseFrag()")
        assertTrue(row > screen.indexOf("AudioQualityFrag()"))
        assertTrue(row < screen.indexOf("AdaptiveQueueFrag()"))
    }

    @Test
    fun `the whole app is told the mode in force, once, from the setting`() {
        val main = source("MainActivity.kt")
        assertTrue("LocalBatteryMode provides BatteryUse.inForce(chosenBatteryMode)" in main)
        assertTrue(Regex("""val LocalBatteryMode = compositionLocalOf \{ BatteryUse\.modeOf\(null\) \}""").containsMatchIn(main))
    }

    @Test
    fun `nothing a mode does writes a setting`() {
        val rule = source("playback/BatteryUse.kt")
        assertFalse("dataStore" in rule)
        assertFalse("edit" in rule)
        // Its row keeps its own key. It reads Download on Wi-Fi only, to tell the downloads already
        // queued at once, and never takes that setting's setter.
        val row = body(source("ui/screens/settings/fragments/PlayerFrag.kt"), "fun ColumnScope.BatteryUseFrag()")
        val kept = Regex("""remember(?:Enum)?Preference\((\w+)""").findAll(row).map { it.groupValues[1] }.toList()
        assertEquals(listOf("BatteryModeKey", "DownloadOnWifiOnlyKey"), kept)
        assertTrue(Regex("""val \((\w+)\) = rememberPreference\(DownloadOnWifiOnlyKey""").containsMatchIn(row))
        assertFalse("dataStore" in row)
    }
}

/** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
private fun stripComments(text: String): String =
    text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
