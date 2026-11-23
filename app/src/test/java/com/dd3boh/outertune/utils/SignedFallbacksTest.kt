/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The update check and the donors ask GitHub and a signed copy on intertune.app together, and take the newer;
 * the news is believed only signed (SignedDocument). What is read from those copies, which one wins, and where
 * the app is wired to them.
 */
class SignedFallbacksTest {

    private val dir = File("src/test/resources/signed")
    private val keys = Json.parseToJsonElement(File(dir, "test-keys.json").readText()).jsonObject
    private val now = keys["now"]!!.jsonPrimitive.long * 1000 + 3_600_000
    private val main = SignedDocument.key(keys["main"]!!.jsonObject["spki"]!!.jsonPrimitive.content, spare = false)

    private fun payload(name: String, kind: String) =
        (SignedDocument.open(File(dir, name).readText(), kind, listOf(main), 0, emptySet(), now) as SignedDocument.Accepted).payload

    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `latest json gives what the GitHub release gives`() {
        val update = latestUpdate(payload("latest.json", SignedDocument.UPDATE))!!
        assertEquals(104, update.versionCode)
        assertEquals("0.13", update.versionName)
        assertEquals("https://github.com/ItzSkyeYT/InterTune/releases/tag/v0.13", update.releaseUrl)
        assertEquals("https://github.com/ItzSkyeYT/InterTune/releases/download/v0.13/InterTune-0.13-core-release-104.apk", update.downloadUrl)
        assertEquals(14L, update.sizeBytes)
        assertEquals("**InterTune 0.13** is out.\n", update.changelog)
    }

    @Test
    fun `a latest json without the apk or a page to open offers nothing`() {
        val whole = """{"versionCode":104,"versionName":"0.13","releaseUrl":"https://intertune.app/download","apk":{"url":"https://intertune.app/a-release-104.apk","size":5}}"""
        assertEquals(104, latestUpdate(whole)!!.versionCode)
        assertNull(latestUpdate(whole.replace(""","apk":{"url":"https://intertune.app/a-release-104.apk","size":5}""", "")))
        assertNull(latestUpdate(whole.replace("https://intertune.app/download", "javascript:alert(1)")))
        assertNull(latestUpdate(whole.replace(""""versionCode":104,""", "")))
        assertNull(latestUpdate("not a document"))
    }

    @Test
    fun `of GitHub's release and the signed copy's, the higher version code wins`() {
        fun update(code: Int, from: String) = UpdateChecker.Update(code, "v$code", "https://$from/page", "https://$from/a-release-$code.apk", 1, "")
        val github = update(104, "github.com")
        val site = update(105, "intertune.app")
        // GitHub still answering with an old release cannot hide a newer one.
        assertEquals(site, newerUpdate(github, site))
        assertEquals(github, newerUpdate(github, update(103, "intertune.app")))
        assertEquals("the same version: GitHub's own release", github, newerUpdate(github, update(104, "intertune.app")))
        assertEquals(site, newerUpdate(null, site))
        assertEquals(github, newerUpdate(github, null))
        assertNull(newerUpdate(null, null))
    }

    @Test
    fun `the signed donors win only when they add names to the README's`() {
        val readme = listOf("Dismorphism", "Tom & Jerry")
        // A README left behind cannot hide a newer donor.
        assertEquals(readme + "New Donor", Donors.newerNames(readme, readme + "New Donor"))
        // A signed copy left behind never wins: the same names, fewer, or one changed since.
        assertEquals(readme, Donors.newerNames(readme, readme))
        assertEquals(readme + "Added Later", Donors.newerNames(readme + "Added Later", readme))
        assertEquals(listOf("Dismo", "Tom & Jerry"), Donors.newerNames(listOf("Dismo", "Tom & Jerry"), readme + "New Donor"))
        // One alone is taken as it is.
        assertEquals(readme, Donors.newerNames(null, readme))
        assertEquals(readme, Donors.newerNames(readme, null))
        assertNull(Donors.newerNames(null, null))
    }

    @Test
    fun `the signed donors are the README's names`() {
        assertEquals(listOf("Dismorphism", "Tom & Jerry"), Donors.signedNames(payload("donors.json", SignedDocument.DONORS)))
        assertNull(Donors.signedNames("""{"donors":"Dismorphism"}"""))
        assertEquals(listOf("A"), Donors.signedNames("""{"donors":["A","A"," ",3,"${"x".repeat(61)}"]}"""))
    }

    @Test
    fun `GitHub and the signed copy are asked together every time, and the newer wins`() {
        val checker = source("utils/UpdateChecker.kt")
        assertTrue(checker.contains("val site = async { signedLatest() }"))
        assertTrue(checker.contains("val update = newerUpdate(fromGitHub, fromSite) ?: return@withContext null"))
        assertTrue(checker.contains("""const val LATEST_URL = "https://intertune.app/news/latest.json""""))
        assertTrue(checker.contains("SignedSources.one(context, LATEST_URL, SignedDocument.UPDATE, SignedUpdateSeqKey)"))
        val donors = source("utils/Donors.kt")
        assertTrue(donors.contains("val site = async { SignedSources.one(context, SIGNED_URL, SignedDocument.DONORS, SignedDonorsSeqKey)?.let(::signedNames) }"))
        assertTrue(donors.contains("val names = newerNames(readme?.let(::parse), signed)"))
        assertTrue(donors.contains("""const val SIGNED_URL = "https://intertune.app/news/donors.json""""))
    }

    @Test
    fun `the news is read only from what was signed, at start too`() {
        val checker = source("utils/PollChecker.kt")
        // The unsigned copy older builds kept is only ever removed, never read.
        val uses = Regex("""CachedPollsJsonKey""").findAll(checker).count()
        val removals = Regex("""remove\(CachedPollsJsonKey\)""").findAll(checker).count()
        assertEquals("CachedPollsJsonKey is read somewhere", uses - 1, removals)
        assertTrue(checker.contains("it[SignedNewsKey] = taken.payload"))
        assertTrue(checker.contains("SignedDocument.open(it, SignedDocument.NEWS, SigningKeys.BUILT_IN, highest, revoked, now)"))
        assertTrue(checker.contains("val cached = context.dataStore.get(SignedNewsKey, \"\")"))
        val app = source("App.kt")
        assertTrue(app.contains("dataStore[SignedNewsKey]?.takeIf { SignedDocument.inDate(it, System.currentTimeMillis()) },"))
        assertTrue(!app.contains("CachedPollsJsonKey"))
    }

    @Test
    fun `only a debug build can be given other hosts`() {
        val checker = source("utils/PollChecker.kt")
        val sources = checker.substringAfter("private fun sources(): Pair<List<String>, List<String>> {").substringBefore("return Polls.SOURCES")
        assertTrue(sources.trim().startsWith("if (BuildConfig.DEBUG) {"))
        assertTrue(checker.contains("private const val SOURCES_STAND_IN = \"news-sources.txt\""))
        assertEquals(1, Regex("""SOURCES_STAND_IN\)""").findAll(checker).count())
    }

    @Test
    fun `what leaves the phone names every host it may ask, in both languages`() {
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("polls_enabled_info", "news_enabled_info")) {
                val text = Regex("""<string name="$name">(.*?)</string>""").find(strings)!!.groupValues[1]
                for (host in listOf("GitHub", "intertune.app", "Cloudflare", "GitLab")) assertTrue("$name in $path: $host", host in text)
            }
            for (name in listOf(
                "update_check_description", "update_check_description_fdroid", "oobe_update_check_description",
                "oobe_update_check_description_fdroid", "setup_updates_line", "setup_updates_line_fdroid",
            )) {
                val text = Regex("""<string name="$name">(.*?)</string>""").find(strings)!!.groupValues[1]
                for (host in listOf("GitHub", "intertune.app")) assertTrue("$name in $path: $host", host in text)
            }
        }
    }
}
