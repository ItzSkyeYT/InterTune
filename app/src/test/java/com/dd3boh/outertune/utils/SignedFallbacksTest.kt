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
 * When GitHub does not answer, the update check and the donors read a signed copy on intertune.app, and the news
 * is believed only signed (SignedDocument). What is read from those copies, and where the app is wired to them.
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
    fun `the signed donors are the README's names`() {
        assertEquals(listOf("Dismorphism", "Tom & Jerry"), Donors.signedNames(payload("donors.json", SignedDocument.DONORS)))
        assertNull(Donors.signedNames("""{"donors":"Dismorphism"}"""))
        assertEquals(listOf("A"), Donors.signedNames("""{"donors":["A","A"," ",3,"${"x".repeat(61)}"]}"""))
    }

    @Test
    fun `GitHub first, and the signed copy only when it does not answer`() {
        val checker = source("utils/UpdateChecker.kt")
        assertTrue(checker.contains("val fromSite = if (body == null) signedLatest() else null"))
        assertTrue(checker.contains("""const val LATEST_URL = "https://intertune.app/news/latest.json""""))
        assertTrue(checker.contains("SignedSources.one(context, LATEST_URL, SignedDocument.UPDATE, SignedUpdateSeqKey)"))
        val donors = source("utils/Donors.kt")
        assertTrue(donors.contains("val names = readme?.let(::parse)"))
        assertTrue(donors.contains("SignedSources.one(context, SIGNED_URL, SignedDocument.DONORS, SignedDonorsSeqKey)"))
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
        val sources = checker.substringAfter("private fun sources(): List<String> {").substringBefore("return Polls.SOURCES")
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
                for (host in listOf("GitHub", "intertune.app", "Cloudflare", "Codeberg")) assertTrue("$name in $path: $host", host in text)
            }
            for (name in listOf("update_check_description", "update_check_description_fdroid")) {
                val text = Regex("""<string name="$name">(.*?)</string>""").find(strings)!!.groupValues[1]
                for (host in listOf("GitHub", "intertune.app")) assertTrue("$name in $path: $host", host in text)
            }
        }
    }
}
