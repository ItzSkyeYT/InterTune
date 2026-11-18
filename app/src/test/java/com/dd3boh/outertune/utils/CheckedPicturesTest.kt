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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A picture of the news is named in the signed document by its SHA-256 and the hosts that have it, and shown
 * only when the bytes match (CheckedPictures), so a host can serve it but not swap it.
 */
class CheckedPicturesTest {

    private val picture = "the release picture".toByteArray()
    private val sha = CheckedPictures.sha256(picture)
    private val url = "https://raw.githubusercontent.com/ItzSkyeYT/InterTune/v0.13/docs/announcements/release-0.13.webp"

    private fun document(sha256: String = sha, mirrors: String = """["https://intertune.app/news/p/$sha.webp","javascript:alert(1)","https://gitlab.com/x/p/$sha.webp"]""") =
        """{"announcements":[],"pictures":{"$url":{"sha256":"$sha256","mirrors":$mirrors}}}"""

    @Test
    fun `the document's pictures are read with their hash and their other hosts, web addresses only`() {
        val read = CheckedPictures.read(document())
        assertEquals(
            CheckedPicture(url, sha, listOf("https://intertune.app/news/p/$sha.webp", "https://gitlab.com/x/p/$sha.webp")),
            read[url],
        )
        assertEquals(listOf(url, "https://intertune.app/news/p/$sha.webp", "https://gitlab.com/x/p/$sha.webp"), read[url]!!.sources)
    }

    @Test
    fun `a hash that is not one leaves the picture out of the list`() {
        assertTrue(CheckedPictures.read(document(sha256 = "1234")).isEmpty())
        assertTrue(CheckedPictures.read(document(sha256 = sha.uppercase())).isNotEmpty())
        assertTrue(CheckedPictures.read("""{"pictures":"no"}""").isEmpty())
        assertTrue(CheckedPictures.read(null).isEmpty())
    }

    @Test
    fun `a picture the document names by its hash is loaded checked, any other by its address`() {
        CheckedPictures.use(document())
        assertEquals(sha, (CheckedPictures.model(url) as CheckedPicture).sha256)
        assertEquals("https://example.org/a.png", CheckedPictures.model("https://example.org/a.png"))
        CheckedPictures.use(null)
        assertEquals(url, CheckedPictures.model(url))
    }

    @Test
    fun `a host with other bytes is passed over for the next, and none matching gives nothing`() {
        val hosts = listOf("https://raw.githubusercontent.com/a.webp", "https://intertune.app/news/p/a.webp", "https://gitlab.com/a.webp")
        val notes = mutableListOf<String>()
        val served = mapOf(hosts[0] to "a swapped picture".toByteArray(), hosts[1] to picture)
        assertArrayEquals(picture, CheckedPictures.firstMatching(hosts, sha, { served[it] }, notes))
        assertEquals(
            listOf(
                "picture ${sha.take(12)} from host 1 (raw.githubusercontent.com): not the picture the document names, passed over",
                "picture ${sha.take(12)} from host 2 (intertune.app): matches its hash",
            ),
            notes,
        )

        val none = mutableListOf<String>()
        assertNull(CheckedPictures.firstMatching(hosts, sha, { if (it == hosts[2]) throw java.io.IOException("down") else "wrong".toByteArray() }, none))
        assertEquals(3, none.size)
        assertTrue(none[2], none[2].endsWith("not there"))
    }

    @Test
    fun `the pictures tools announce names are read here, with every host`() {
        val dir = File("src/test/resources/signed")
        val keys = Json.parseToJsonElement(File(dir, "test-keys.json").readText()).jsonObject
        val now = keys["now"]!!.jsonPrimitive.long * 1000 + 3_600_000
        val main = SignedDocument.key(keys["main"]!!.jsonObject["spki"]!!.jsonPrimitive.content, spare = false)
        val news = SignedDocument.open(File(dir, "news.json").readText(), SignedDocument.NEWS, listOf(main), 0, emptySet(), now) as SignedDocument.Accepted
        val known = CheckedPictures.read(news.payload)[url]!!
        assertEquals(CheckedPictures.sha256("RIFF....WEBPVP8 the release picture".toByteArray()), known.sha256)
        assertEquals(
            listOf(
                "https://intertune.app/news/p/${known.sha256}.webp",
                "https://intertune-count.intertune.workers.dev/news/p/${known.sha256}.webp",
                "https://gitlab.com/itzskyeyt/intertune-news/-/raw/main/news/p/${known.sha256}.webp",
            ),
            known.mirrors,
        )
    }

    @Test
    fun `every picture of the news goes through the check, and the loader knows how`() {
        val ui = listOf("ui/component/AnnouncementUi.kt", "ui/component/PollUi.kt")
            .map { File("src/main/java/com/dd3boh/outertune/$it").readText() }
        for (screen in ui) {
            assertTrue("a picture still loads from its address alone", !Regex("""\bmodel = url,""").containsMatchIn(screen))
        }
        assertEquals(3, Regex("""model = CheckedPictures\.model\(url\)""").findAll(ui[0]).count())
        assertEquals(1, Regex("""model = CheckedPictures\.model\(url\)""").findAll(ui[1]).count())
        val app = File("src/main/java/com/dd3boh/outertune/App.kt").readText()
        // Both image loaders, with the disk cache and without.
        assertEquals(2, Regex("""add\(CheckedPictureFetcher\.Factory\(cacheDir\.resolve\(CheckedPictures\.FOLDER\)\)\)""").findAll(app).count())
        assertEquals(2, Regex("""add\(CheckedPictureKeyer\(\)\)""").findAll(app).count())
        val checker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()
        assertTrue(checker.contains("CheckedPictures.use(json)"))
    }
}
