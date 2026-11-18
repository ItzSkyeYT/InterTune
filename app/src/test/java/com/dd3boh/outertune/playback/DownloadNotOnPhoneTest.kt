/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.UnknownHostException

/**
 * Two downloaded songs of many failed offline as "No network connection" (#179, 14 Nov 2026, 0.12.9).
 *
 * True, and it told the person nothing: they had downloaded the songs so that they would play without one.
 * The player had found the song wanting on the phone and gone to YouTube for it. Which of three things it was
 * (a download holding part of the song, a file gone from the download folder or not yet listed, the download
 * under another id) the log could not say, and neither did it say where in the song the read began, which is
 * how the one cause found so far was found (a read near the end, fixed in 167a9e38c).
 */
class DownloadNotOnPhoneTest {

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val screen = File("src/main/java/com/dd3boh/outertune/ui/player/ThumbnailPlaybackError.kt").readText()

    @Test
    fun `the log line says how much of the song the phone has and where`() {
        assertEquals(
            "[aaaaaaaaaaa] counted as downloaded, but not read from the phone from byte 0, and there is no network: " +
                "the download cache holds 1048576 of its 3412000 bytes, no download folder has a file for it",
            DownloadNotOnPhone.said("aaaaaaaaaaa", 0, 1_048_576, 3_412_000, inFolder = false),
        )
        assertTrue(DownloadNotOnPhone.said("aaaaaaaaaaa", 3_300_000, 0, -1, inFolder = true)
            .endsWith("from byte 3300000, and there is no network: the download cache holds none of it, a download folder has a file for it now"))
        assertTrue(DownloadNotOnPhone.said("aaaaaaaaaaa", 0, 500, -1, inFolder = false).contains("holds 500 bytes of it, its length not known"))
    }

    @Test
    fun `it is found under the player's wrappers`() {
        val error = PlaybackException("This song is marked as downloaded", DownloadNotOnPhone(UnknownHostException("music.youtube.com")),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        val asThePlayerHasIt = PlaybackException("Source error", java.io.IOException("Unexpected", Recovery.ResolveNoAnswer(error)), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertTrue(DownloadNotOnPhone.isIt(asThePlayerHasIt))
        assertFalse(DownloadNotOnPhone.isIt(PlaybackException("Source error", UnknownHostException("music.youtube.com"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse(DownloadNotOnPhone.isIt(null))
    }

    @Test
    fun `a song counted as downloaded that the network was asked for says so, and still waits for the network`() {
        val mapped = service.substringAfter("is ConnectException, is UnknownHostException ->").substringBefore("is SocketTimeoutException ->")
        assertTrue(mapped, mapped.trimStart().startsWith("if (downloadUtil.isDownloaded(mediaId)) PlaybackException("))
        assertTrue(mapped, "getString(R.string.err_download_not_on_phone)" in mapped)
        assertTrue(mapped, "downloadNotOnPhone(mediaId, position, throwable, said)" in mapped)
        // Any other song, as before.
        assertTrue(mapped, "getString(R.string.error_no_internet)" in mapped)
        // Both keep the network failure's code: the service waits for the network, and back online the song streams.
        assertEquals(2, Regex("PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED").findAll(mapped).count())
        assertTrue(NetworkRetryPolicy.waitsForNetwork(offline = true, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, localSong = false))
    }

    @Test
    fun `the log says which of its causes it is, from the download cache and the folders`() {
        val helper = service.substringAfter("private fun downloadNotOnPhone(mediaId: String, position: Long, failure: Throwable, said: String): DownloadNotOnPhone {")
            .substringBefore("\n        }\n")
        assertTrue(helper, "ContentMetadata.getContentLength(downloadCache.getContentMetadata(mediaId))" in helper)
        assertTrue(helper, "downloadCache.getCachedBytes(mediaId, 0, C.LENGTH_UNSET.toLong())" in helper)
        assertTrue(helper, "downloadUtil.localMgr.isExists(mediaId)?.exists() == true" in helper)
        assertTrue(helper, "Log.w(TAG, \"\$said: \${DownloadNotOnPhone.said(mediaId, position, held, length, inFolder)}\")" in helper)
        // Reading the caches never fails the song a second time.
        assertEquals(3, Regex("runCatching \\{").findAll(helper).count())
    }

    @Test
    fun `the resolve line says where in the song the read begins`() {
        val resolve = service.substringAfter("fun resolve(mediaId: String, position: Long, length: Long, ahead: Boolean = false): ResolvedSong {")
        assertTrue(resolve.substringBefore("val queued ="), "Log.i(TAG, if (position > 0L) \"\$said: song id = \$mediaId, from byte \$position\" else \"\$said: song id = \$mediaId\")" in resolve.substringBefore("val queued ="))
    }

    @Test
    fun `the player's error screen says it in plain words`() {
        assertTrue("val isDownloadNotOnPhone = remember(error) { DownloadNotOnPhone.isIt(error) }" in screen)
        assertTrue(screen.substringAfter("} else if (isDownloadNotOnPhone) {").trimStart().startsWith("stringResource(R.string.err_download_not_on_phone)"))
    }

    @Test
    fun `its words are in English and French, two short sentences`() {
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val text = Regex("""<string name="err_download_not_on_phone">([^<]+)</string>""").find(File(path).readText())?.groupValues?.get(1)
            assertTrue(path, text != null)
            assertEquals(path, 2, text!!.count { it == '.' })
            assertTrue(path, text.length < 170)
        }
    }
}
