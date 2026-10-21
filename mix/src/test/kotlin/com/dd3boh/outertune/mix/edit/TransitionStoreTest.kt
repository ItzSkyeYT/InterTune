/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The saved changes and the fixes to songs' beats: the fake for tests and the file on the phone
 * keep the same rules, and the file survives what a phone does to it (a kill mid-write, a damaged
 * file, an entry from a later build).
 */
class TransitionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fade = Specs.fadeOnBands()
    private val custom = Specs.vocalsOverCustom().copy(a = Specs.a.copy(song = "midnight-city"))
    private val problems = mutableListOf<String>()

    private fun file() = File(folder.root, "mix/transitions.json")
    private fun fileStore() = JsonFileTransitionStore(file()) { message, _ -> problems += message }

    /** The rules every TransitionStore keeps. */
    private fun keepsTheRules(store: TransitionStore) {
        assertNull(store.get(fade.pair))
        assertEquals(emptyList<TransitionSpec>(), store.all())
        store.put(fade)
        store.put(custom)
        assertEquals(fade, store.get(fade.pair))
        assertEquals(custom, store.get(custom.pair))
        assertNull(store.get(SongPair(fade.pair.to, fade.pair.from)))

        val blend = fade.copy(preset = Preset.BLEND)
        store.put(blend)
        assertEquals(blend, store.get(fade.pair))
        assertEquals(setOf(blend, custom), store.all().toSet())

        assertTrue(store.delete(fade.pair))
        assertFalse(store.delete(fade.pair))
        assertNull(store.get(fade.pair))

        val broken = fade.copy(moves = fade.moves - LaneId.of("A.lows")!!)
        assertTrue(runCatching { store.put(broken) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(store.get(fade.pair))

        store.put(fade)
        assertEquals(2, store.clear())
        assertEquals(emptyList<TransitionSpec>(), store.all())
        assertEquals(0, store.clear())
    }

    @Test
    fun `the fake keeps the rules`() = keepsTheRules(InMemoryTransitionStore())

    @Test
    fun `the file keeps the rules`() {
        keepsTheRules(fileStore())
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `what is saved is there for the next store on the same file, and no half file is left`() {
        fileStore().apply {
            put(fade)
            put(custom)
            put(fade.copy(plain = true))
        }
        val again = fileStore()
        assertEquals(fade.copy(plain = true), again.get(fade.pair))
        assertEquals(custom, again.get(custom.pair))
        assertEquals(listOf("transitions.json"), file().parentFile.list()!!.sorted())
        assertTrue(file().readText().startsWith("{\"v\":1,\"transitions\":["))
    }

    @Test
    fun `a damaged file is kept aside and the store starts empty`() {
        file().parentFile.mkdirs()
        file().writeText("{\"v\":1,\"transitions\":[{\"v\":1,")
        val store = fileStore()
        assertNull(store.get(fade.pair))
        assertEquals(1, problems.size)
        assertTrue(File(file().parentFile, "transitions.json.unreadable").exists())
        store.put(fade)
        assertEquals(fade, fileStore().get(fade.pair))
    }

    @Test
    fun `a file from a later build is kept aside, not overwritten`() {
        file().parentFile.mkdirs()
        file().writeText("{\"v\":2,\"transitions\":[]}")
        val store = fileStore()
        assertEquals(emptyList<TransitionSpec>(), store.all())
        assertEquals("{\"v\":2,\"transitions\":[]}", File(file().parentFile, "transitions.json.unreadable").readText())
    }

    @Test
    fun `an entry this build cannot play gives Auto, and is kept when others are saved`() {
        val later = fade.toJson().replaceFirst("{\"v\":1,", "{\"v\":2,")
        file().parentFile.mkdirs()
        file().writeText("{\"v\":1,\"transitions\":[$later]}")
        val store = fileStore()
        assertNull(store.get(fade.pair))
        assertEquals(emptyList<TransitionSpec>(), store.all())
        store.put(custom)
        assertTrue(later in file().readText())
        assertTrue(fileStore().delete(fade.pair))
        assertFalse(later in file().readText())
        assertEquals(custom, fileStore().get(custom.pair))
    }

    @Test
    fun `a disk that refuses the write leaves the old file and the store as they were`() {
        val store = fileStore()
        store.put(fade)
        // A directory where the next file goes: it cannot be written.
        File(file().parentFile, "transitions.json.new").mkdirs()
        assertTrue(runCatching { store.put(custom) }.isFailure)
        assertNull(store.get(custom.pair))
        assertEquals(fade, fileStore().get(fade.pair))
    }

    @Test
    fun `fixes to a song's beat are kept per song, and Reset forgets them`() {
        val fix = BeatFix(bpm = 111.0, downbeatShift = 1, nudgeMs = -10, source = "251:4820311:312")
        val file = File(folder.root, "mix/beat-fixes.json")
        for (store in listOf(InMemoryBeatFixStore(), JsonFileBeatFixStore(file))) {
            assertNull(store.get("wake-me-up"))
            store.put("wake-me-up", fix)
            store.put("after-hours", BeatFix(nudgeMs = 20))
            assertEquals(fix, store.get("wake-me-up"))
            assertEquals(mapOf("wake-me-up" to fix, "after-hours" to BeatFix(nudgeMs = 20)), store.all())
            store.put("after-hours", BeatFix.NONE)
            assertNull(store.get("after-hours"))
            assertFalse(store.delete("after-hours"))
        }
        assertEquals(fix, JsonFileBeatFixStore(file).get("wake-me-up"))
        assertTrue(JsonFileBeatFixStore(file).delete("wake-me-up"))
        assertNull(JsonFileBeatFixStore(file).get("wake-me-up"))
    }

    @Test
    fun `the app's files are where the design says`() {
        val files = folder.newFolder("files")
        JsonFileTransitionStore.at(files).put(fade)
        JsonFileBeatFixStore.at(files).put("wake-me-up", BeatFix(nudgeMs = 10))
        assertTrue(File(files, "mix/transitions.json").isFile)
        assertTrue(File(files, "mix/beat-fixes.json").isFile)
    }
}
