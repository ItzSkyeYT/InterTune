/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.cacheDirectoryTree
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.ui.utils.getDirectoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Found 18 Nov 2026 (the review of local-remove, L1): a removal or an Undo clears the kept folder trees on one thread
 * (LocalRemoval) while the folders on the back stack build theirs again on others. The count that numbers the
 * folders, the keys of the Folders tab's rows, went back to 0 at each clear and lost counts between threads, so two
 * folders of one list could share a number ("Key was already used"), and the kept trees, a plain list, could be read
 * while being cleared (ConcurrentModificationException). Either closed the app.
 */
class DirectoryTreeKeysTest {

    private fun song(path: String) = Song(
        SongEntity(id = "LS$path", title = path.substringAfterLast('/'), isLocal = true, localPath = path),
        artists = emptyList(),
    )

    @Test
    fun `folders built on several threads while the kept trees are cleared never share a number`() {
        val uids = ConcurrentLinkedQueue<Int>()
        val stop = AtomicBoolean(false)
        val clearer = thread { while (!stop.get()) clearDtCache() }
        val builders = (1..6).map { t ->
            thread {
                repeat(300) { n ->
                    val root = DirectoryTree("/storage/emulated/0/Music", CulmSongs(0))
                    for (i in 0 until 5) {
                        val path = "/storage/emulated/0/Music/t$t-$n-$i/song.mp3"
                        root.insert(path.removePrefix("/storage/emulated/0/Music/"), song(path))
                    }
                    uids += root.uid
                    root.subdirs.mapTo(uids) { it.uid }
                }
            }
        }
        builders.forEach { it.join() }
        stop.set(true)
        clearer.join()
        assertEquals(6 * 300 * 6, uids.size)
        assertEquals("every folder its own number", uids.size, uids.toSet().size)
    }

    @Test
    fun `the kept trees can be read, filled and cleared from several threads at once`() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = (1..4).map { t ->
            thread {
                try {
                    repeat(3000) { n ->
                        when ((n + t) % 3) {
                            0 -> cacheDirectoryTree(DirectoryTree("/storage/emulated/0/F$t-$n", CulmSongs(0)))
                            1 -> getDirectoryTree("/storage/emulated/0/F$t-${n - 1}")
                            else -> clearDtCache()
                        }
                    }
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        workers.forEach { it.join() }
        clearDtCache()
        assertTrue(failures.joinToString { it.toString() }, failures.isEmpty())
    }

    @Test
    fun `the count is taken in one step and never set back, and every use of the kept trees holds one lock`() {
        val sources = File("src/main/java/com/dd3boh/outertune")
        val tree = File(sources, "models/DirectoryTree.kt").readText()
        assertTrue("val uid = nextUid.getAndIncrement()" in tree)
        assertTrue("private val nextUid = AtomicInteger(0)" in tree)
        val everything = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("directoryUID" in everything)
        val utils = File(sources, "ui/utils/LocalMediaUtils.kt").readText()
        for (use in listOf("fun getDirectoryTree(", "fun cacheDirectoryTree(", "fun clearDtCache(")) {
            val body = utils.substringAfter(use).substringBefore("\n}\n")
            assertTrue(use, "synchronized(dtCacheLock) {" in body)
        }
        assertEquals("private val cachedDirectoryTree: ArrayList<DirectoryTree> = ArrayList()", utils.lines().single { "cachedDirectoryTree:" in it })
    }
}
