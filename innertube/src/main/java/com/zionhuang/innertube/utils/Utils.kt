package com.zionhuang.innertube.utils

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.PlaylistPage
import java.security.MessageDigest

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatching {
    val page = getOrThrow()
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    while (continuation != null) {
        val continuationPage = YouTube.playlistContinuation(continuation).getOrThrow()
        songs += continuationPage.songs
        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = null,
        continuation = page.continuation
    )
}

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    val items = page.items.toMutableList()
    var continuation = page.continuation
    while (continuation != null) {
        // Keep what we have rather than losing it. getOrThrow here meant one failed continuation
        // threw away every item already parsed from the first page, so a partial success became a
        // completely missing row.
        val continuationPage = YouTube.libraryContinuation(continuation).getOrNull() ?: break
        items += continuationPage.items
        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = page.continuation
    )
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .associate {
            val (key, value) = it.split("=")
            key to value
        }

/**
 * A length as YouTube writes it, in seconds: "3:34", or "3.34" in the languages that put a dot
 * there (Indonesian, Finnish and Danish, 5 Nov 2026), and "1:02:03" with hours. Only colons were
 * read, and a radio's songs whose length cannot be read are left out, so in those languages a
 * radio stopped after its first song (#83). Anything else, views or a year, is null.
 */
fun String.parseTime(): Int? {
    val parts = trim().split(':', '.')
    if (parts.size !in 2..3 || parts.any { part -> part.isEmpty() || !part.all { it.isDigit() } }) return null
    // Minutes and seconds after the first part are always two digits and under 60.
    if (parts.drop(1).any { it.length != 2 }) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.drop(1).any { it >= 60 }) return null
    return numbers.fold(0) { total, part -> total * 60 + part }
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}