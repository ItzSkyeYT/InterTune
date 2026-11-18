/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song played while it downloads at a Download quality of its own begins from the download's
 * part, so the player holds the download's stream. Past that part, the address the player kept from
 * an earlier play of the song is another stream: taken, the song was the download's bytes up to
 * where its part ended and the other stream's after. It goes on only when it is the download's.
 */
class KeptAddressTest {

    private val opus = 251
    private val aac = 140

    @Test
    fun `the address of the download's own stream goes on`() {
        assertTrue(keptAddressGoesOn(opus, 3_000_000, 3_000_000, opus, 3_000_000))
    }

    @Test
    fun `another quality than the download's does not, by its format row or by the download's own record`() {
        // The row the download wrote as it began names its stream.
        assertFalse(keptAddressGoesOn(opus, 3_000_000, 3_000_000, aac, 1_900_000))
        // The player fetched since and rewrote the row: the download's cache still knows its length.
        assertFalse(keptAddressGoesOn(aac, 1_900_000, 3_000_000, aac, 1_900_000))
        // No length recorded: the row decides.
        assertFalse(keptAddressGoesOn(opus, 3_000_000, -1, aac, 1_900_000))
    }

    @Test
    fun `what cannot be read says nothing against it`() {
        assertTrue(keptAddressGoesOn(null, null, -1, opus, 3_000_000))
        assertTrue(keptAddressGoesOn(opus, null, -1, opus, 3_000_000))
        assertTrue(keptAddressGoesOn(opus, 3_000_000, -1, opus, 0))
    }

    // ---- the resolver (no player on the JVM: the source is read, as the other wiring tests do)

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long, ahead: Boolean = false)")
        .substringBefore("private fun fetch(")

    @Test
    fun `the resolver notes which songs began from the download cache, at their start only`() {
        val note = resolve.substringAfter("downloadCache.holdsFrom(mediaId, position").substringBefore("downloadUtil.localMgr.isExists")
        assertTrue("if (position == 0L) {" in note)
        assertTrue("if (isDownload) startedInDownload.add(mediaId) else startedInDownload.remove(mediaId)" in note)
    }

    @Test
    fun `past the start the kept address, and the SABR start held for it, are taken only when they are the download's stream`() {
        val known = resolve.substringAfter("val known = addresses.of(mediaId, System.currentTimeMillis())")
            .substringBefore("val one = fetches")
        val check = known.substringAfter("val notTheDownloads =").substringBefore("if (notTheDownloads")
        assertTrue("position > 0L && mediaId in startedInDownload" in check)
        assertTrue("keptAddressGoesOn(row?.itag, row?.contentLength, recordedLength(downloadCache, mediaId), format.itag, format.contentLengthOrZero())" in check)
        assertTrue("known?.takeIf { !staleQuality && !notTheDownloads }" in known)
        assertTrue("formats[mediaId]?.takeIf { !staleQuality && !notTheDownloads }" in known)
        // Asked once: what the fetch brings is what the song goes on with, or every reopen would walk again.
        val once = known.substringAfter("if (notTheDownloads) {").substringBefore("known?.takeIf")
        assertTrue("startedInDownload.remove(mediaId)" in once)
        // Not taken, the song goes to fetch(), whose RouteWish names the format row's stream mid-song.
        val fetch = service.substringAfter("private fun fetch(").substringBefore("val resolveBegan")
        assertTrue("if (position > 0L) runCatching { database.readOrNull { formatRow(mediaId) } }" in fetch)
    }
}
