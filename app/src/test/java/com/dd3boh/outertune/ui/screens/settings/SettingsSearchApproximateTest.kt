/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.R
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search with what people really type: a typo, the other word for a thing ("dark mode",
 * "amoled"), a British spelling, words in another order, a word too many ("make the lyrics
 * bigger"). Each search has the settings that are a right answer; the right one has to be among
 * the first three results. Prints the table with -i. On 26 Oct 2026, 38 of the 70 did before
 * typing mistakes, run-together words, the closest matches and screen names were found, 67 after,
 * 69 with two more search words;
 * the floor keeps it there.
 */
class SettingsSearchApproximateTest {

    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun strings(folder: String): Map<String, String> =
        File("src/main/res/$folder").listFiles()!!.filter { it.name.startsWith("strings") }.flatMap { file ->
            Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }.toList()
        }.toMap()

    private val english = strings("values")
    private val index = SettingsSearchIndex.of({ id -> english[nameOf[id]] ?: error("no text for $id") }, engine = true)

    private val searches: List<Pair<String, List<String>>> = listOf(
        // typos and other spellings
        "equaliser" to listOf("Equalizer"),
        "equalzer" to listOf("Equalizer"),
        "normalisation" to listOf("Audio normalization"),
        "sleep timr" to listOf("Sleep timer on a schedule", "Timer length"),
        "battry" to listOf("Battery use"),
        "cahce" to listOf("Maximum song cache size", "Clear song cache", "Maximum artwork cache size", "Clear image cache"),
        "downlaod wifi" to listOf("Download on Wi-Fi only"),
        "proxi" to listOf("Enable proxy"),
        "updtes" to listOf("Updates", "Check for updates", "Tell me about updates"),
        "backgrund" to listOf("Player background style"),
        "langage" to listOf("Default content language"),
        "scrobbel" to listOf("Scrobble what I play"),
        "lyircs font" to listOf("Font size"),
        "colours" to listOf("Living blur colours", "Enable dynamic theme"),
        "skip silense" to listOf("Skip silence"),
        "karaokee" to listOf("Karaoke style lyrics."),
        // the other word for it
        "dark mode" to listOf("Dark theme"),
        "darkmode" to listOf("Dark theme"),
        "night theme" to listOf("Dark theme"),
        "amoled" to listOf("Pure black"),
        "black background" to listOf("Pure black", "Player background style"),
        "loudness" to listOf("Audio normalization", "Fix song volumes"),
        "volume" to listOf("Audio normalization", "Fix song volumes"),
        "bitrate" to listOf("Audio quality"),
        "sound quality" to listOf("Audio quality"),
        "music quality" to listOf("Audio quality"),
        "high quality" to listOf("Audio quality"),
        "timer" to listOf("Sleep timer on a schedule", "Timer length"),
        "auto backup" to listOf("Back up automatically"),
        "spotify" to listOf("Import from another app"),
        "import playlists" to listOf("Import from another app"),
        "lyrics size" to listOf("Font size"),
        "romaji" to listOf("Show in Latin letters"),
        "romanize lyrics" to listOf("Show in Latin letters"),
        "local files" to listOf("Enable local media", "Configure scan locations…"),
        "music folder" to listOf("Configure scan locations…", "Set external download folder…"),
        "offline" to listOf("Download on Wi-Fi only", "Automatically download your liked songs", "Download all liked songs now"),
        "delete downloads" to listOf("Clear all downloads"),
        "incognito" to listOf("Pause listen history", "Pause search history"),
        "vpn" to listOf("Enable proxy"),
        "headphones" to listOf("Your headphones", "Resume when Bluetooth connects"),
        "car" to listOf("Android Auto"),
        "material you" to listOf("Enable dynamic theme"),
        "accent colour" to listOf("Enable dynamic theme"),
        "region" to listOf("Default content country"),
        "power saving" to listOf("Battery use", "Follow the phone's battery saver"),
        "battery saver" to listOf("Follow the phone's battery saver", "Battery use"),
        "auto update" to listOf("Download updates automatically", "Check in the background"),
        "version" to listOf("Installed version"),
        "sign in" to listOf("Login"),
        "sign out" to listOf("Log out"),
        "logout" to listOf("Log out"),
        "last fm" to listOf("Connect a Last.fm account", "Scrobble what I play"),
        "3d audio" to listOf("Spatial audio"),
        "surround" to listOf("Spatial audio"),
        "battery optimization" to listOf("Prevent system killing app", "Battery use"),
        "crash" to listOf("Submit a bug report (Via GitHub)"),
        "report a bug" to listOf("Submit a bug report (Via GitHub)"),
        "donate" to listOf("Support InterTune"),
        "changelog" to listOf("What is new"),
        "tutorial" to listOf("Show me around", "Tour of the settings"),
        "start screen" to listOf("Default open tab"),
        "gestures" to listOf("Swipe to queue", "Swipe horizontally to change song", "Swipe the mini player away"),
        // another order, a word too many
        "theme dark" to listOf("Dark theme"),
        "turn on dark theme" to listOf("Dark theme"),
        "change audio quality" to listOf("Audio quality"),
        "make the lyrics bigger" to listOf("Font size"),
        "clear the cache" to listOf("Clear song cache", "Clear image cache", "Clear cached artwork"),
        "stop history" to listOf("Pause listen history", "Pause search history"),
        "keep app alive" to listOf("Prevent system killing app"),
    )

    @Test
    fun `what people type finds the setting they mean among the first three`() {
        var first = 0
        var three = 0
        val rows = StringBuilder()
        for ((typed, right) in searches) {
            val found = index.search(typed).map { it.title }
            val at = found.indexOfFirst { it in right }
            if (at == 0) first++
            if (at in 0..2) three++
            rows.append(String.format("%-26s %-6s %s%n", typed, if (at < 0) "miss" else "#${at + 1}", found.take(3).joinToString(" | ")))
        }
        println("SEARCH TRIAL ${searches.size} searches: $first first, $three in the first three, ${searches.size - three} not\n$rows")
        assertTrue("$three of ${searches.size} in the first three", three * 100 >= FLOOR_PERCENT * searches.size)
    }

    private companion object {
        /** In the first three for this share of the searches, at least. */
        const val FLOOR_PERCENT = 90
    }
}
