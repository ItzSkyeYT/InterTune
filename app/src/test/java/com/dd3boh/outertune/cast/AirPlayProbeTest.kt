/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A scripted session against experiments/features/airplay/fake_receiver.py, the real two seconds
 * of latency and all: run only when AIRPLAY_FAKE names it (`127.0.0.1:5000`), so the full check
 * never waits on it.
 *
 *     python3 -I fake_receiver.py --port 5000 --expect counter --seconds 40 --out <dir>
 *     AIRPLAY_FAKE=127.0.0.1:5000 ./gradlew :app:testCoreDebugUnitTest --tests '*AirPlayProbeTest'
 *
 * The song is the counter signal (CounterSignal): 6 s, played 2 s, paused 1 s, played on, sought to
 * 3 s and played to its end, then a 3 s song that follows it in the same stream, then the volume
 * and the hang-up. What the receiver should find: three stretches (FLUSH at the pause and at the
 * seek), each a run of consecutive frames, the first from 0, the second from where the pause was
 * heard, the third from frame 132300 through the end of the song and on into the second song.
 */
class AirPlayProbeTest {
    @Test
    fun `a scripted session against the fake receiver`() = runBlocking {
        val target = System.getenv("AIRPLAY_FAKE")
        assumeTrue("AIRPLAY_FAKE is not set", !target.isNullOrBlank())
        val host = target!!.substringBefore(':')
        val port = target.substringAfter(':', "5000").toInt()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val songs = CounterSongServer()
        val device = AirPlayDevices.of("00112233@Probe", host, port, mapOf("et" to "0,4", "cn" to "0,1,2,3", "tp" to "UDP", "md" to "0,1,2"))
        val link = AirPlayLink(device, scope)
        val start = System.nanoTime()
        fun say(what: String) = println("%7.3f s  %s".format((System.nanoTime() - start) / 1e9, what))
        scope.launch { link.status.collect { say("status ${it.link} ${it.state} load ${it.load} at ${it.positionMs} ms (reported ${it.reportedAt})") } }
        try {
            link.connect()
            link.setVolume(0.5f)
            link.load(songs.item("counter", 6L * 44_100), 0, play = true)
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.PLAYING && it.load == 1 } }
            Thread.sleep(2_000)
            link.pause()
            say("paused at ${link.status.value.positionMs} ms")
            Thread.sleep(1_000)
            link.play()
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.PLAYING && it.load == 1 } }
            Thread.sleep(1_000)
            link.seek(3_000)
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.PLAYING && it.load == 1 } }
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.ENDED && it.load == 1 } }
            link.load(songs.item("second", 3L * 44_100), 0, play = true)
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.PLAYING && it.load == 2 } }
            withTimeout(10_000) { link.status.first { it.state == ReceiverState.ENDED && it.load == 2 } }
            Thread.sleep(3_000)
            link.setVolume(0.25f)
            say("songs fetched: ${songs.requests.joinToString(" | ")}")
        } finally {
            link.close()
            Thread.sleep(500)
            songs.close()
            scope.cancel()
        }
    }
}
