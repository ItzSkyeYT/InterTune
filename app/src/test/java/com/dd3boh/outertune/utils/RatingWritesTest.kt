/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * A song's ratings reach YouTube Music in the order they were tapped (#171): a Dislike still on its
 * way, then Remove dislike, used to be able to land the other way round. Run on real threads, three at
 * a time as syncCoroutine sends them.
 */
class RatingWritesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun stop() = scope.cancel()

    @Test
    fun `one song's writes go out in tap order, a slow one first included`() = runBlocking {
        val writes = InOrder(scope)
        val last = CompletableDeferred<Unit>()
        writes.send("song") { delay(300); sent += "dislike" }
        writes.send("song") { sent += "removelike" }
        writes.send("song") { delay(20); sent += "like"; last.complete(Unit) }
        withTimeout(5_000) { last.await() }
        assertEquals(listOf("dislike", "removelike", "like"), sent.toList())
    }

    @Test
    fun `another song does not wait behind a slow write`() = runBlocking {
        val writes = InOrder(scope)
        val other = (0..100).map { "song$it" }.first { writes.lane(it) != writes.lane("song") }
        val held = CompletableDeferred<Unit>()
        val otherSent = CompletableDeferred<Unit>()
        val firstSent = CompletableDeferred<Unit>()
        writes.send("song") { held.await(); sent += "song"; firstSent.complete(Unit) }
        writes.send(other) { sent += other; otherSent.complete(Unit) }
        withTimeout(5_000) { otherSent.await() }
        assertEquals(listOf(other), sent.toList())
        held.complete(Unit)
        withTimeout(5_000) { firstSent.await() }
        assertEquals(listOf(other, "song"), sent.toList())
    }

    @Test
    fun `a write that throws does not stop the ones after it`() = runBlocking {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writes = InOrder(scope) { failures += it }
        val after = CompletableDeferred<Unit>()
        writes.send("song") { throw IllegalStateException("offline") }
        writes.send("song") { sent += "after"; after.complete(Unit) }
        withTimeout(5_000) { after.await() }
        assertEquals(listOf("after"), sent.toList())
        assertEquals(1, failures.size)
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    @Test
    fun `the heart, Dislike and Remove dislike all go through the one queue`() {
        val like = source("db/entities/SongEntity.kt").substringAfter("fun toggleLike() = copy(").substringBefore("fun toggleLibrary()")
        assertTrue("RatingWrites.send(id) {" in like)
        assertTrue("YouTube.likeVideo(id, !liked)" in like)
        assertFalse("launch" in like)
        val dontPlay = source("ui/menu/DontPlaySong.kt")
        assertTrue("RatingWrites.send(song.id) {" in dontPlay)
        assertFalse("CoroutineScope(" in dontPlay)
        // Nowhere else sends a song's rating; SyncUtils' likeSong (no caller today) goes through it too.
        assertTrue("RatingWrites.send(s.id) {" in source("utils/SyncUtils.kt"))
        val senders = sources.walkTopDown().filter { it.extension == "kt" }
            .filter { file -> Regex("""YouTube\.(likeVideo|dislikeVideo)\(""").containsMatchIn(source(file.relativeTo(sources).path)) }
            .map { it.relativeTo(sources).path }.toSet()
        assertEquals(setOf("db/entities/SongEntity.kt", "ui/menu/DontPlaySong.kt", "utils/SyncUtils.kt"), senders)
    }
}
