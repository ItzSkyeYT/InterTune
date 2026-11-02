/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.BatteryModeKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.ProxyEnabledKey
import com.dd3boh.outertune.constants.ProxyTypeKey
import com.dd3boh.outertune.constants.ProxyUrlKey
import com.dd3boh.outertune.constants.RecogniseListenSecondsKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.playback.BatteryUse
import com.dd3boh.outertune.recognition.MicrophoneListener
import com.dd3boh.outertune.utils.ReportSettings.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The few settings a report carries (ReportSettings). His guard of 2 Nov 2026: only the ones that
 * matter for the area of the problem, mostly those changed from their default, never the whole list.
 */
class ReportSettingsTest {
    private fun prefs(vararg pairs: Preferences.Pair<*>): Preferences = mutablePreferencesOf(*pairs)

    @Test
    fun `at their defaults every setting of the area is listed as such, none marked`() {
        val lines = ReportSettings.lines(Area.PLAYBACK, prefs())
        assertEquals(
            listOf(
                "Settings (playback): none changed",
                "At default: audio quality auto,",
                "  normalisation on, skip silence off,",
                "  offload off, equalizer off,",
                "  spatial audio off, transition fade off,",
                "  skip on error off, proxy off,",
                "  battery use balanced",
                "Also: signed out, song cache off",
            ),
            lines,
        )
        assertTrue(lines.none { "*" in it })
    }

    @Test
    fun `what is changed from the default comes first, one to a line and marked, and is no longer among the rest`() {
        val lines = ReportSettings.lines(
            Area.PLAYBACK,
            prefs(
                AudioQualityKey to "HIGH", SkipSilenceKey to true, AudioNormalizationKey to false,
                BatteryModeKey to "SAVER", MaxSongCacheSizeKey to 256,
                InnerTubeCookieKey to "SAPISID=secretvalue; HSID=other",
            ),
        )
        assertEquals(
            listOf(
                "Settings (playback), * = not the default:",
                "* audio quality high",
                "* normalisation off",
                "* skip silence on",
                "* battery use saver",
                "At default: offload off, equalizer off,",
                "  spatial audio off, transition fade off,",
                "  skip on error off, proxy off",
                "Also: signed in, song cache 256 MB",
            ),
            lines,
        )
        // Written back to its default, a setting is not changed.
        assertFalse("*" in ReportSettings.lines(Area.PLAYBACK, prefs(SkipSilenceKey to false, AudioQualityKey to "AUTO")).joinToString("\n"))
    }

    @Test
    fun `no line of the settings is wider than a phone shows, bar a single item`() {
        val everything = prefs(
            AudioQualityKey to "HIGH", SkipSilenceKey to true, AudioNormalizationKey to false, BatteryModeKey to "SAVER",
            ProxyEnabledKey to true, ProxyTypeKey to "SOCKS", InnerTubeCookieKey to "SAPISID=a",
        )
        for (area in Area.entries) {
            for (line in ReportSettings.lines(area, everything, listOf("microphone not allowed"))) {
                assertTrue("$area: ${line.length} in $line", line.length <= ReportSettings.WIDTH + 2 || !line.contains(", "))
            }
        }
        val a = "a".repeat(20); val b = "b".repeat(20); val c = "c".repeat(20)
        assertEquals(listOf("At default: $a,", "  $b, $c"), ReportSettings.wrapped("At default: ", listOf(a, b, c)))
        assertEquals(listOf("Also: signed out, song cache off"), ReportSettings.wrapped("Also: ", listOf("signed out", "song cache off")))
    }

    @Test
    fun `the song cache is told as the rule of every reader has it, off where never stored and 512 MB in Saver`() {
        assertEquals("off", ReportSettings.songCache(prefs()))
        assertEquals("off", ReportSettings.songCache(prefs(MaxSongCacheSizeKey to 0)))
        assertEquals("256 MB", ReportSettings.songCache(prefs(MaxSongCacheSizeKey to 256)))
        assertEquals("no limit", ReportSettings.songCache(prefs(MaxSongCacheSizeKey to -1)))
        assertEquals("512 MB", ReportSettings.songCache(prefs(BatteryModeKey to "SAVER")))
        assertEquals("256 MB", ReportSettings.songCache(prefs(BatteryModeKey to "SAVER", MaxSongCacheSizeKey to 256)))
    }

    @Test
    fun `signed in is told and the cookie never is`() {
        val text = ReportSettings.lines(Area.PLAYBACK, prefs(InnerTubeCookieKey to "SAPISID=secretvalue; HSID=other")).joinToString("\n")
        assertTrue("signed in" in text)
        assertFalse("secretvalue" in text)
        assertFalse("SAPISID" in text)
        assertTrue("signed out" in ReportSettings.lines(Area.PLAYBACK, prefs(InnerTubeCookieKey to "VISITOR_INFO1_LIVE=abc")).joinToString())
        // A cookie that cannot be read is not a crash.
        assertTrue("signed out" in ReportSettings.lines(Area.PLAYBACK, prefs(InnerTubeCookieKey to "a=b=c")).joinToString())
    }

    @Test
    fun `the proxy is told as on or off and its kind, never its address`() {
        val on = ReportSettings.lines(Area.PLAYBACK, prefs(ProxyEnabledKey to true, ProxyTypeKey to "SOCKS", ProxyUrlKey to "203.0.113.7:1080"))
        assertTrue("* proxy on, socks" in on)
        assertFalse("203.0.113.7" in on.joinToString())
        assertTrue("proxy off" in ReportSettings.lines(Area.PLAYBACK, prefs(ProxyUrlKey to "203.0.113.7:1080")).joinToString("\n"))
    }

    @Test
    fun `a folder is told as custom, never its name`() {
        val text = ReportSettings.lines(
            Area.DOWNLOADS,
            prefs(DownloadPathKey to "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FMel", DownloadOnWifiOnlyKey to true),
        ).joinToString("\n")
        assertTrue("* download folder custom" in text)
        assertTrue("* Wi-Fi only on" in text)
        assertFalse("Mel" in text)
        assertFalse("content://" in text)
        assertTrue("download folder default" in ReportSettings.lines(Area.DOWNLOADS, prefs()).joinToString())
    }

    @Test
    fun `each area has its own short set, and the whole list is never shown`() {
        val inArea = Area.entries.associateWith { ReportSettings.specs(it).map { s -> s.label } }
        for ((area, labels) in inArea) {
            assertTrue("$area has ${labels.size}", labels.size in 3..10)
            assertEquals("a setting twice in $area", labels.size, labels.toSet().size)
        }
        // The ones he named for playback.
        for (named in listOf("audio quality", "normalisation", "skip silence", "offload", "equalizer", "proxy", "battery use")) {
            assertTrue("$named is not in playback", named in inArea.getValue(Area.PLAYBACK))
        }
        // A few dozen settings in all, of the hundreds there are.
        assertTrue("${ReportSettings.all.size}", ReportSettings.all.size <= 32)
        // Nothing that holds an account, an address or a name.
        val keys = ReportSettings.all.map { it.key.name.lowercase() }
        for (word in listOf("cookie", "visitor", "datasync", "email", "accountname", "channel", "url", "lastfm", "session", "token", "history")) {
            assertTrue("$word in $keys", keys.none { word in it })
        }
    }

    @Test
    fun `lyrics, recognition and casting have their own sets`() {
        assertTrue("KuGou on" in ReportSettings.lines(Area.LYRICS, prefs()).joinToString("\n"))
        assertTrue("listen length 12 s" in ReportSettings.lines(Area.RECOGNITION, prefs()).joinToString("\n"))
        assertTrue("* listen length 20 s" in ReportSettings.lines(Area.RECOGNITION, prefs(RecogniseListenSecondsKey to 20)))
        val cast = ReportSettings.lines(Area.CASTING, prefs(AudioQualityKey to "LOW"))
        assertTrue("* audio quality low" in cast)
        assertTrue(cast.none { "skip silence" in it })
        // Beside the settings, what is not one but bears on the area.
        assertTrue("Also: signed out, microphone not allowed" in ReportSettings.lines(Area.RECOGNITION, prefs(), listOf("microphone not allowed")))
    }

    @Test
    fun `a report of a run before this one says its settings are as they are now`() {
        assertTrue(ReportSettings.lines(Area.PLAYBACK, prefs(), now = true)[0].startsWith("Settings now (playback)"))
    }

    @Test
    fun `a change is told by the setting's name and its new value, and only for listed settings`() {
        val before = prefs(AudioQualityKey to "AUTO")
        val after = prefs(AudioQualityKey to "HIGH", SkipSilenceKey to true, DownloadPathKey to "content://x/tree/y", ProxyUrlKey to "203.0.113.7:1080")
        assertEquals(
            listOf("audio quality" to "high", "skip silence" to "on", "download folder" to "custom"),
            ReportSettings.changes(before, after),
        )
        // Something else changed (an account, a time, a screen's sort order): not seen at all.
        assertTrue(ReportSettings.changes(prefs(), prefs(InnerTubeCookieKey to "SAPISID=a", MaxSongCacheSizeKey to 1)).isEmpty())
        assertTrue(ReportSettings.changes(after, after).isEmpty())
    }

    @Test
    fun `a signed in change is told by itself`() {
        assertFalse(ReportSettings.signedIn(prefs()))
        assertTrue(ReportSettings.signedIn(prefs(InnerTubeCookieKey to "SAPISID=a")))
    }

    // ---- which area ----

    @Test
    fun `the area is the fault's, else the latest warning's, else the screen's, else playback`() {
        assertEquals(Area.PLAYBACK, ReportSettings.areaOf("MusicService", listOf("LyricsHelper"), "settings/library/lyrics"))
        assertEquals(Area.DOWNLOADS, ReportSettings.areaOf("DownloadUtil", emptyList()))
        // A fault in the app's own plumbing says nothing of an area: playback, and not the lyrics warning before it.
        assertEquals(Area.PLAYBACK, ReportSettings.areaOf("App", listOf("LyricsHelper")))
        assertEquals(Area.LYRICS, ReportSettings.areaOf(null, listOf("MusicService", "LyricsHelper", "System.err")))
        assertEquals(Area.RECOGNITION, ReportSettings.areaOf(null, listOf("System.err"), "recognition/history"))
        assertEquals(Area.CASTING, ReportSettings.areaOf(null, listOf("ChromecastLink", "System.err")))
        assertEquals(Area.PLAYBACK, ReportSettings.areaOf(null, listOf("System.err"), "settings/privacy"))
        assertEquals(Area.PLAYBACK, ReportSettings.areaOf(null, emptyList(), null))
    }

    @Test
    fun `every log tag the app writes with maps to an area or to none, by its name`() {
        assertEquals(Area.LYRICS, ReportSettings.areaOfTag("LyricsCard"))
        assertEquals(Area.LYRICS, ReportSettings.areaOfTag("KeptLyrics"))
        assertEquals(Area.RECOGNITION, ReportSettings.areaOfTag("RecognitionEngine"))
        assertEquals(Area.RECOGNITION, ReportSettings.areaOfTag("ShazamClient"))
        assertEquals(Area.CASTING, ReportSettings.areaOfTag("CastSession"))
        assertEquals(Area.CASTING, ReportSettings.areaOfTag("FCastLink"))
        assertEquals(Area.CASTING, ReportSettings.areaOfTag("RemotePlayer"))
        assertEquals(Area.PLAYBACK, ReportSettings.areaOfTag("ExoPlayerImplInternal"))
        assertEquals(Area.PLAYBACK, ReportSettings.areaOfTag("YTPlayerUtils"))
        assertEquals(null, ReportSettings.areaOfTag("System.err"))
        assertEquals(null, ReportSettings.areaOfTag("App"))
    }

    // ---- the defaults are the app's ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The names the code gives each preference: "skipSilence" is SkipSilenceKey. */
    private val keyNames: Map<String, String> =
        Regex("""val (\w+Key) = \w+PreferencesKey\("([^"]+)"\)""")
            .findAll(File(sources, "constants/PreferenceKeys.kt").readText())
            .associate { it.groupValues[2] to it.groupValues[1] }

    private val code: List<String> = sources.walkTopDown()
        .filter { it.extension == "kt" && it.name != "ReportSettings.kt" && it.name != "PreferenceKeys.kt" }
        .flatMap { it.readLines() }
        .filter { ".save(" !in it && "edit" !in it }
        .toList()

    /** What the code reads [key] as when it was never set: every literal or constant it gives after the key's name. */
    private fun defaultsInCode(key: String): List<String> {
        val read = Regex("""\b$key\b(?:\]\s*\?:\s*|\]\.toEnum\(\s*(?:defaultValue\s*=\s*)?|\s*,\s*(?:defaultValue\s*=\s*)?)([A-Za-z_][A-Za-z_0-9.]*|\d+)""")
        return code.flatMap { line -> read.findAll(line).map { it.groupValues[1] }.toList() }
    }

    private fun lastWord(token: String) = token.removeSuffix(".name").substringAfterLast('.')

    @Test
    fun `each default is the one the screens and the readers of the setting use`() {
        for (spec in ReportSettings.all) {
            val name = keyNames[spec.key.name] ?: error("${spec.key.name} is not a key of PreferenceKeys.kt")
            val found = defaultsInCode(name)
            when (spec) {
                is ReportSettings.Flag -> {
                    val literals = found.filter { it == "true" || it == "false" }
                    assertTrue("$name is read with no default anywhere", literals.isNotEmpty())
                    assertEquals("$name: ${spec.default} here, $literals in the code", emptyList<String>(), literals.filter { it != spec.default.toString() })
                }
                is ReportSettings.Choice -> {
                    val enums = found.filter { it.contains('.') && !it.endsWith(".toString") }.map(::lastWord)
                        .map { if (it == "DEFAULT") BatteryUse.DEFAULT.name else it }
                    assertTrue("$name is read with no default anywhere", enums.isNotEmpty())
                    assertEquals("$name: ${spec.default} here, $enums in the code", emptyList<String>(), enums.filter { it != spec.default })
                }
                is ReportSettings.Count -> {
                    val numbers = found.mapNotNull { if (it == "MicrophoneListener.DEFAULT_SECONDS") MicrophoneListener.DEFAULT_SECONDS else it.toIntOrNull() }
                    assertTrue("$name is read with no default anywhere", numbers.isNotEmpty())
                    assertEquals("$name: ${spec.default} here, $numbers in the code", emptyList<Int>(), numbers.filter { it != spec.default })
                }
                is ReportSettings.Custom -> assertNotNull(spec.word)
                else -> Unit
            }
        }
    }

    @Test
    fun `the proxy's default is off and its kind HTTP, as the privacy screen has them`() {
        assertTrue("false" in defaultsInCode("ProxyEnabledKey"))
        assertTrue("Proxy.Type.HTTP" in defaultsInCode("ProxyTypeKey"))
        assertTrue("* proxy on, http" in ReportSettings.lines(Area.PLAYBACK, prefs(ProxyEnabledKey to true)))
    }
}
