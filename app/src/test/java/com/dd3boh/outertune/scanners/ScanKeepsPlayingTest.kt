/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A folder scan leaves the music alone (15 Nov 2026: "when i'm listening to a ytm song and scan a
 * folder, it pauses my music"). Both scans, the one in Settings and the one at launch, paused the
 * player before they began, upstream's way of keeping a queue that is about to be read again from
 * the database quiet (OuterTune ea0aaaf08, "Re-init queue after scan"). The scan touches no song
 * that is not a local file, the queues are rebuilt without asking anything of the player
 * (MusicService.initQueue), and a local song that plays on from its open file does not need the
 * scan to wait for it. These guard the rule at the source, as the other tests here do.
 */
class ScanKeepsPlayingTest {
    private val main = "src/main/java/com/dd3boh/outertune"

    /** The code of [path] under the app's sources, its comments left out: a comment saying why nothing is paused is not a pause. */
    private fun code(path: String): String = codeOf(File("$main/$path"))

    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    /** What pauses, stops or empties a player. */
    private val stops = Regex(
        """\.pause\(\)|\.stop\(\)|playWhenReady\s*=\s*false|setPlayWhenReady\(\s*false|\.clearMediaItems\(\)|stopForRestore"""
    )

    private val scanLaunch = "MainActivityUtils.kt"
    private val scanSettings = "ui/screens/settings/fragments/LocalMediaSettingsFrag.kt"

    @Test
    fun `no scan pauses or stops the player`() {
        val files = listOf(File("$main/$scanLaunch"), File("$main/$scanSettings")) +
                File("$main/utils/scanners").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue("the scan sources are where they were", files.size > 4 && files.all { it.exists() })
        files.forEach { file ->
            val found = stops.findAll(codeOf(file)).map { it.value }.toList()
            assertEquals("${file.name} stops or pauses the player: $found", emptyList<String>(), found)
        }
    }

    @Test
    fun `the scan at launch and the scan in Settings both still read the queues again after they ran`() {
        // What the pause stood beside: a local song the scan moved or retagged is the new one in the queue, which
        // is where the player looks first for its file (MusicService.resolve). Not given up with the pause.
        listOf(scanLaunch, scanSettings).forEach { path ->
            assertTrue("$path refreshes the queues after the scan", "service?.refreshQueuesAfterScan()" in code(path))
        }
    }

    @Test
    fun `and reads them through the one call that asks nothing of the player`() {
        // Not initQueue itself: with Persistent queue off it has nothing to read back and empties the board, which
        // under music that goes on playing left a player with no queue.
        listOf(scanLaunch, scanSettings).forEach { path ->
            assertTrue("$path calls initQueue directly", "initQueue(" !in code(path))
        }
        val service = code("playback/MusicService.kt")
        val refresh = service.substringAfter("suspend fun refreshQueuesAfterScan(").substringBefore("\n    }\n")
        assertTrue("the call is in MusicService", "suspend fun refreshQueuesAfterScan(" in service)
        val guard = refresh.indexOf("PersistentQueueKey")
        val read = refresh.indexOf("initQueue()")
        assertTrue("it looks at Persistent queue first", guard in 0 until read)
        assertTrue("and does nothing when it is off", "return" in refresh.substring(guard, read))
        listOf("activePlayer", "exoPlayer", "player.", "playWhenReady", "setCurrQueue").forEach {
            assertTrue("refreshQueuesAfterScan touches the player: $it", it !in refresh)
        }
        assertEquals("nothing that pauses or stops", emptyList<String>(), stops.findAll(refresh).map { it.value }.toList())
    }
}
