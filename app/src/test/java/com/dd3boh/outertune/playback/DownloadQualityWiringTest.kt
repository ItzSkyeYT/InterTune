/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Downloads in their own quality (Unreleased.DOWNLOAD_QUALITY), read from the source as the other
 * wiring tests are, since a flag is a constant of the build and a resolver cannot be run here: the
 * resolver of DownloadUtil judges the cached copy, picks the stream and records the copy by one
 * value, the row is offered only with the flag, and its texts are held with their French beside them.
 * What the quality is, for a setting and a flag, is DownloadQualityTest.
 */
class DownloadQualityWiringTest {

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The text without its comments, so a sentence that names a thing is not taken for a use of it. */
    private fun code(text: String) = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun fileCode(path: String) = code(File(sources, path).readText())

    private val util = fileCode("playback/DownloadUtil.kt")

    /** The resolver every download passes through, from its factory to the notification helper after it. */
    private val resolver = util.substringAfter("private val dataSourceFactory = ResolvingDataSource.Factory(").substringBefore("val downloadNotificationHelper")

    // ---- the resolver

    @Test
    fun `the resolver asks for one quality, read once, and its three uses go through it`() {
        assertTrue("the resolver was not found", "isStaleQualityTier(" in resolver)
        assertEquals(1, Regex("""\bval quality = qualityNow\(\)""").findAll(resolver).count())
        // The cached copy is judged by it, the stream is picked by it, and the copy is recorded at it.
        assertTrue("isStaleQualityTier(stored?.qualityTier, quality, metered = metered(), storedBitrate = stored?.bitrate)" in resolver)
        assertTrue("YTPlayerUtils.playerResponseForPlayback(id, audioQuality = quality, connectivityManager = connectivityManager, forDownload = true)" in resolver)
        assertTrue("qualityTier = cacheTier(quality, metered())" in resolver)
        // And the Audio quality setting itself is not read in the resolver: only its name as the walk's argument is left.
        val strays = Regex("""\baudioQuality\b""").findAll(resolver.replace("audioQuality = quality,", "")).count()
        assertEquals("the playback setting is read in the resolver again", 0, strays)
    }

    @Test
    fun `the quality is read before the resolver looks at the cached copy, so a change in the middle splits nothing`() {
        val read = resolver.indexOf("val quality = qualityNow()")
        assertTrue(read in 0 until resolver.indexOf("isStaleQualityTier("))
        assertTrue(read < resolver.indexOf("YTPlayerUtils.playerResponseForPlayback("))
        assertTrue(read < resolver.indexOf("qualityTier = cacheTier("))
    }

    @Test
    fun `the playback setting is read in one place only, to be given to qualityForDownload with the Download quality`() {
        assertEquals(1, Regex("""qualityForDownload\(downloadQuality, audioQuality\)""").findAll(util).count())
        assertTrue("private val downloadQuality by enumPreference(context, DownloadQualityKey, DownloadQuality.SAME)" in util)
        assertTrue("private fun qualityNow(): AudioQuality = qualityForDownload(downloadQuality, audioQuality)" in util)
        // The declaration, that one read, and the name of the walk's argument in the resolver.
        assertEquals(3, Regex("""\baudioQuality\b""").findAll(util).count())
    }

    // ---- the flag

    @Test
    fun `the flag is held back like the others of debug and preview builds`() {
        assertTrue(Regex("""^\s*val DOWNLOAD_QUALITY = BuildConfig\.DEBUG\s*$""", RegexOption.MULTILINE).containsMatchIn(fileCode("constants/Unreleased.kt")))
    }

    @Test
    fun `without the flag the stored setting is not read, and only the download code, its row and the settings index read the flag`() {
        val readers = sources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Unreleased.kt" }
            .filter { Regex("""Unreleased\.DOWNLOAD_QUALITY\b""").containsMatchIn(code(it.readText())) }
            .map { it.relativeTo(sources).path }
            .toSet()
        assertEquals(
            setOf("playback/DownloadUtil.kt", "ui/screens/settings/fragments/DownloadsFrag.kt", "ui/screens/settings/SettingsIndex.kt"),
            readers,
        )
        val rule = util.substringAfter("fun qualityForDownload(").substringBefore("\n\n")
        assertTrue("offered: Boolean = Unreleased.DOWNLOAD_QUALITY" in rule)
        assertTrue("if (offered) setting.tier ?: playback else playback" in rule)
    }

    // ---- the row

    @Test
    fun `the row is among the download settings, behind the flag, drawn as Audio quality is`() {
        val frag = fileCode("ui/screens/settings/fragments/DownloadsFrag.kt")
        val start = frag.indexOf("if (Unreleased.DOWNLOAD_QUALITY) {")
        assertTrue("the row is not behind the flag", start > 0)
        // After Download on Wi-Fi only, before Download liked songs.
        assertTrue(start > frag.indexOf("if (heldOn) SaverHoldsLabel()"))
        assertTrue(start < frag.indexOf("Tour.SETTING_LIKED_AUTODOWNLOAD"))
        val row = frag.substring(start).substringAfter("{").substringBefore("\n    }\n")
        assertTrue("EnumListPreference(" in row)
        assertTrue("rememberEnumPreference(DownloadQualityKey, DownloadQuality.SAME)" in row)
        assertTrue("Modifier.settingRow(R.string.download_quality)" in row)
        assertTrue("Text(stringResource(R.string.download_quality))" in row)
        assertTrue("Icons.Rounded.HighQuality" in row)
        // Same as playback in its own words; the three tiers in the ones the Audio quality row gives them.
        assertTrue("DownloadQuality.SAME -> stringResource(R.string.download_quality_same)" in row)
        assertTrue("DownloadQuality.MAX -> stringResource(R.string.audio_quality_max)" in row)
        assertTrue("DownloadQuality.HIGH -> stringResource(R.string.audio_quality_high)" in row)
        assertTrue("DownloadQuality.LOW -> stringResource(R.string.audio_quality_low)" in row)
        assertTrue("MeaningLine(stringResource(R.string.download_quality_description))" in row)
        // All four choices, in the order of the enum, and no list of its own that could leave one out or add Auto.
        assertFalse("values =" in row)
        assertFalse("AUTO" in row)
        // It writes the setting and nothing else: no download is touched when it changes.
        assertFalse("downloadUtil" in row)
    }

    @Test
    fun `its place in the search of Settings is the Storage screen, behind the same flag, found by the audio quality words`() {
        val line = fileCode("ui/screens/settings/SettingsIndex.kt").lines().filter { "R.string.download_quality," in it }
        assertEquals(1, line.size)
        assertTrue("Tour.ROUTE_STORAGE" in line.single())
        assertTrue("words = listOf(R.string.search_words_audio_quality)" in line.single())
        assertTrue("there = Unreleased.DOWNLOAD_QUALITY" in line.single())
    }

    @Test
    fun `the setting is stored by name under its own key`() {
        assertTrue("val DownloadQualityKey = stringPreferencesKey(\"downloadQuality\")" in fileCode("constants/PreferenceKeys.kt"))
    }

    // ---- the texts

    private val english = File("src/main/res/values/strings-ot.xml").readText()
    private val french = File("src/main/res/values-fr/strings-ot.xml").readText()
    private val names = listOf("download_quality", "download_quality_same", "download_quality_description")

    private fun text(xml: String, name: String) = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(xml)?.groupValues?.get(1)

    @Test
    fun `its three texts are held from translators, with their French beside them`() {
        for (name in names) {
            val tag = Regex("""<string name="$name"[^>]*>""").find(english)?.value
            assertNotNull("$name is not in the English file", tag)
            assertTrue("$name is not held (translatable=\"false\")", "translatable=\"false\"" in tag!!)
            assertNotNull("$name has no French", text(french, name))
        }
        assertEquals("Download quality", text(english, "download_quality"))
        assertEquals("Same as playback", text(english, "download_quality_same"))
        assertEquals("Qualité des téléchargements", text(french, "download_quality"))
        assertEquals("Comme la lecture", text(french, "download_quality_same"))
    }

    @Test
    fun `its texts are short and plain, in both languages`() {
        for ((language, xml) in listOf("English" to english, "French" to french)) {
            for (name in names) {
                val text = text(xml, name)!!
                assertFalse("$language $name has a long dash", '—' in text || '–' in text)
                assertFalse("$language $name has an exclamation mark", '!' in text)
                assertTrue("$language $name runs to more than two sentences", Regex("""[.?](?=\s|$)""").findAll(text).count() <= 2)
                assertTrue("$language $name runs to ${text.length} characters", text.length <= 120)
            }
        }
        // The note says the two things that matter: the trade, and that nothing already downloaded is changed.
        val note = text(english, "download_quality_description")!!
        assertTrue("space" in note && "already downloaded" in note)
    }
}
