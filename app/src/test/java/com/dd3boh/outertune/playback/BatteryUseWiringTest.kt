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
        // two can never disagree for a moment, and again when the phone's battery saver changes.
        assertEquals(5, Regex("""dataStore\.data\s*\.combine\(PhoneBatterySaver\.on\) \{ prefs, phoneSaver ->""").findAll(service).count())
        assertEquals(5, Regex("""prefs\.batteryMode\(phoneSaver\)""").findAll(service).count())
        assertTrue(Regex("""private fun Preferences\.batteryMode\(phoneSaver: Boolean\): BatteryMode =\s*BatteryUse\.modeOf\(this\[BatteryModeKey], this\[FollowPhoneSaverKey], phoneSaver\)""").containsMatchIn(service))
    }

    @Test
    fun `every reader of the mode takes the phone's battery saver into account`() {
        // The mode from the stored key alone was round one's: nothing may still ask for it.
        for (file in root.walkTopDown().filter { it.extension == "kt" }) {
            val text = stripComments(file.readText())
            assertFalse("${file.name} asks for the mode without the phone's battery saver", Regex("""BatteryUse\.modeOf\([^,()]*\)""").containsMatchIn(text))
            assertFalse("${file.name} asks for the mode in force without the phone's battery saver", Regex("""BatteryUse\.inForce\([^,()]*\)""").containsMatchIn(text))
        }
        val watch = source("playback/PhoneBatterySaver.kt")
        assertTrue("the change is listened for", "IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)" in watch)
        assertTrue("and read from the system", "isPowerSaveMode" in watch)
        assertTrue("the readers that ask when they act", Regex("""fun Context\.batteryModeNow\(\): BatteryMode =\s*BatteryUse\.modeOf\(dataStore\[BatteryModeKey], dataStore\[FollowPhoneSaverKey], PhoneBatterySaver\.isOn\(this\)\)""").containsMatchIn(watch))
        assertTrue("started with the app", "PhoneBatterySaver.start(this)" in source("App.kt"))
        val main = source("MainActivity.kt")
        // Read again whenever the app is seen, since the broadcast can be missed in the background.
        assertTrue("PhoneBatterySaver.read(this)" in body(main, "override fun onStart()"))
        assertTrue("PhoneBatterySaver.read(this)" in body(main, "override fun onResume()"))
        for (reader in listOf("utils/SyncUtils.kt", "playback/DownloadUtil.kt", "di/AppModule.kt", "viewmodels/HomeViewModel.kt")) {
            assertTrue("$reader does not ask for the mode in force", "batteryModeNow()" in source(reader))
        }
        // Downloads waiting for Wi-Fi follow the phone's saver at once, straight on the manager.
        assertTrue("PhoneBatterySaver.on.drop(1).collect" in source("playback/DownloadUtil.kt"))
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
        val taste = body(service, "private fun tasteFor(")
        assertTrue("BatteryUse.tasteKeptMs(batteryMode, sameRun = run == tasteRun)" in taste)
        // Told the run, so a new radio takes a taste no older than Full keeps it.
        assertTrue("tasteFor(snap.now, snap.view.key)" in service)
        assertEquals("the run is kept with each taste taken", 2, Regex("""tasteRun = run""").findAll(taste).count())
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
    fun `the bars' clock on Balanced stops once paused bars are at rest`() {
        // A paused song's bars on screen would otherwise wake the main thread thirty times a
        // second to draw nothing.
        val bars = source("ui/component/PlayingIndicator.kt")
        val stepped = bars.indexOf("if (pace == BatteryUse.Bars.STEPPED)")
        assertTrue("no stepped bars", stepped >= 0)
        val stops = bars.indexOf("if (!isPlaying && animatables.all { abs(0.15f - it.value) <= ARRIVED }) return@collectLatest", stepped)
        val waits = bars.indexOf("delay(BatteryUse.BARS_STEP_MS)", stepped)
        assertTrue("the clock never stops while paused", stops in stepped until waits)
    }

    @Test
    fun `Battery use has a page of its own, listed under Storage and downloads where the build has the choice`() {
        // It changes the whole app, not only what plays, so it is not under Player and audio (his word, 26 Oct 2026).
        assertFalse("BatteryUseFrag" in source("ui/screens/settings/PlayerSettings.kt"))
        assertTrue("BatteryUseFrag()" in source("ui/screens/settings/BatterySettings.kt"))
        val list = source("ui/screens/settings/SettingsScreen.kt")
        val row = list.indexOf("if (Unreleased.BATTERY_MODES) {\n                PreferenceEntry(\n                    title = { Text(stringResource(R.string.battery_use)) }")
        assertTrue(row > list.indexOf("navController.navigate(\"settings/storage\")"))
        assertTrue(row < list.indexOf("navController.navigate(\"settings/backup\")"))
        assertTrue("navController.navigate(\"settings/battery\")" in list.substring(row))
        val nav = source("ui/navigation/AppNavGraph.kt")
        assertTrue(Regex("""if \(Unreleased\.BATTERY_MODES\) \{\s*screen\("settings/battery"\)""").containsMatchIn(nav))
    }

    @Test
    fun `the whole app is told the mode in force, once, from the setting, its switch and the phone`() {
        val main = source("MainActivity.kt")
        assertTrue("val batteryMode = BatteryUse.inForce(chosenBatteryMode, followPhoneSaver, phoneSaverOn)" in main)
        assertTrue("LocalBatteryMode provides batteryMode" in main)
        assertTrue(Regex("""val LocalBatteryMode = compositionLocalOf \{ BatteryUse\.modeOf\(null, null, phoneSaver = false\) \}""").containsMatchIn(main))
    }

    @Test
    fun `Saver's screen, the black dark theme, the still glass and the slower frame rate, each by its rule`() {
        val main = source("MainActivity.kt")
        assertTrue("the theme", Regex("""BatteryUse\.darkTheme\(if \(darkTheme == DarkMode\.AUTO\) isSystemInDarkTheme else darkTheme == DarkMode\.ON, batteryMode\)""").containsMatchIn(main))
        assertTrue("pure black", "val pureBlack = BatteryUse.pureBlack(chosenPureBlack, batteryMode)" in main)
        // Every other place that works out whether the app is dark asks the same rule, or a
        // player over a black theme would draw the light theme's colours.
        for (file in root.walkTopDown().filter { it.extension == "kt" }) {
            val text = stripComments(file.readText())
            if ("rememberEnumPreference(DarkModeKey" !in text || file.name == "ThemeFrag.kt") continue
            assertTrue("${file.name} works out the theme without Battery use", "BatteryUse.darkTheme(" in text)
        }
        assertTrue("the glass", "val glassStill = BatteryUse.glassStill(batteryMode)" in main)
        assertTrue("LocalGlassStill provides glassStill" in main)
        // Every place that records a layer for the glass or draws a panel from one asks whether
        // the glass is still: a panel reading a layer that was not recorded draws nothing.
        for (file in root.walkTopDown().filter { it.extension == "kt" }) {
            val text = stripComments(file.readText())
            if ("drawBackdrop(" !in text && ".layerBackdrop(" !in text) continue
            assertTrue("${file.name} records or draws glass without asking whether it is still", "LocalGlassStill" in text || "glassStill" in text || ".still" in text)
        }
        assertTrue("the frame rate", "LaunchedEffect(lowFrameRate) { ScreenRate.apply(this@MainActivity, lowFrameRate) }" in main)
        assertTrue("val lowFrameRate = BatteryUse.lowFrameRate(batteryMode)" in main)
        val rate = source("ui/utils/ScreenRate.kt")
        assertTrue("BatteryUse.saverScreenMode(" in rate)
        assertTrue("REQUESTED_FRAME_RATE_CATEGORY_LOW" in rate)
    }

    @Test
    fun `Home's engine rows stand by the rule, and a row that stands reads nothing to be shown`() {
        val home = source("viewmodels/HomeViewModel.kt")
        assertTrue("BatteryUse.rowStands(mode, now - builtAt, sameSession, sameDayPart, since)" in home)
        // No row is kept or built again on the old fixed three hours any more.
        assertFalse("3 * 3_600_000L && session ==" in home)
        assertFalse(Regex("""now - last\w*BuildAt < 3 \* 3_600_000L""").containsMatchIn(home))
        assertTrue("the row nobody sees", "BatteryUse.buildsUnseenRows(context.batteryModeNow())" in home)
        assertTrue("Playing now following the session", "BatteryUse.rowFollowsSession(context.batteryModeNow())" in home)
        // A row that stands is shown without reading the library, whenever that read would be needed.
        val build = body(home, "private suspend fun buildEngineRow(")
        val fast = build.indexOf("engineInputWouldRead(now)")
        assertTrue("the kept row waits on the library's read", fast in 0 until build.indexOf("val input = engineInput(now, mode)"))
        // Nor is the library read at all as Home opens on rows that stand.
        assertTrue(Regex("""if \(!opensOnKeptRows\(\)\) engineInput\(""").containsMatchIn(home))
    }

    @Test
    fun `the radios' taste reads the listen log a little at a time`() {
        assertTrue("EngineLoader.loadHeard(database, now, listenLog)" in body(service, "private fun tasteFor("))
        assertTrue("val rawListens = log?.read(RoomListens(database)) ?: database.engineListens()" in source("engine/EngineLoader.kt"))
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
        assertEquals(listOf("BatteryModeKey", "DownloadOnWifiOnlyKey", "FollowPhoneSaverKey"), kept)
        // The switch under it writes its own key only, and only when it is tapped.
        assertTrue(Regex("""val \(followPhone, onFollowPhoneChange\) = rememberPreference\(FollowPhoneSaverKey""").containsMatchIn(row))
        assertEquals(1, Regex("""onFollowPhoneChange\(""").findAll(row).count())
        assertTrue(Regex("""val \((\w+)\) = rememberPreference\(DownloadOnWifiOnlyKey""").containsMatchIn(row))
        assertFalse("dataStore" in row)
    }
}

/** Block comments, and line comments that start a line or follow a space, so a url in a string stays. */
private fun stripComments(text: String): String =
    text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")
