/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.StreamOrder.Asked
import com.dd3boh.outertune.utils.StreamOrder.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class StreamOrderTest {
    private val wait = StreamOrder.RETRY_REFUSED_MS

    /** The chain as YTPlayerUtils writes it, signed out, and signed in with the account kept for last. */
    private val chain = listOf("ANDROID_VR", "VISIONOS", "IOS")
    private val signedIn = chain + "ANDROID (account)"

    /** 8 Oct 2026, noon. Any moment would do. */
    private val noon = 1_791_453_600_000L

    /** What every phone had learned on 8 Oct 2026: ANDROID_VR refused as a bot, VISIONOS served. */
    private val today = Memory(worked = "VISIONOS", refusedAt = mapOf("ANDROID_VR" to noon))

    // The order to ask in.

    @Test
    fun `with nothing remembered the chain is asked as it is written`() {
        assertEquals(chain, StreamOrder.order(chain, Memory(), noon))
        assertEquals(signedIn, StreamOrder.order(signedIn, Memory(), noon))
    }

    @Test
    fun `the client that worked last is asked first, the others after it as written`() {
        assertEquals(listOf("VISIONOS", "ANDROID_VR", "IOS"), StreamOrder.order(chain, today, noon + 1))
        assertEquals(
            listOf("VISIONOS", "ANDROID_VR", "IOS", "ANDROID (account)"),
            StreamOrder.order(signedIn, today, noon + 1),
        )
        // The last of the chain too, once everything written before it has been refused.
        val ios = Memory("IOS", mapOf("ANDROID_VR" to noon, "VISIONOS" to noon))
        assertEquals(listOf("IOS", "ANDROID_VR", "VISIONOS"), StreamOrder.order(chain, ios, noon + 1))
    }

    @Test
    fun `a chain whose first client worked last is left alone`() {
        val healthy = Memory(worked = "ANDROID_VR")
        assertEquals(chain, StreamOrder.order(chain, healthy, noon))
    }

    @Test
    fun `a refused client is asked first again once the wait is over`() {
        val shortcut = listOf("VISIONOS", "ANDROID_VR", "IOS")
        assertEquals(shortcut, StreamOrder.order(chain, today, noon))
        assertEquals(shortcut, StreamOrder.order(chain, today, noon + wait - 1))
        assertEquals(chain, StreamOrder.order(chain, today, noon + wait))
        assertEquals(chain, StreamOrder.order(chain, today, noon + 30 * wait))
    }

    @Test
    fun `every client written before the one that worked has to be resting`() {
        // IOS worked; ANDROID_VR was refused a moment ago, VISIONOS long enough ago to be due.
        val memory = Memory("IOS", mapOf("ANDROID_VR" to noon, "VISIONOS" to noon - wait))
        assertEquals(chain, StreamOrder.order(chain, memory, noon + 1))
    }

    @Test
    fun `a client nothing is known about is not skipped`() {
        // Written before the one that worked and never seen refused: a client new in this version,
        // or one the last walk did not reach.
        val memory = Memory(worked = "VISIONOS")
        assertEquals(chain, StreamOrder.order(chain, memory, noon))
        val longer = listOf("ANDROID_VR", "TVHTML5", "VISIONOS", "IOS")
        assertEquals(longer, StreamOrder.order(longer, today, noon + 1))
    }

    @Test
    fun `the account's client is never jumped over`() {
        // Asked as the account always, or while YouTube refuses the connection: it stands before
        // VISIONOS, and nothing is ever remembered about it, so the order stays as written.
        val promoted = listOf("ANDROID_VR", "ANDROID (account)", "VISIONOS", "IOS")
        assertEquals(promoted, StreamOrder.order(promoted, today, noon + 1))
    }

    @Test
    fun `a client that is no longer in the chain is not asked`() {
        val gone = Memory("TVHTML5", mapOf("ANDROID_VR" to noon, "VISIONOS" to noon, "IOS" to noon))
        assertEquals(chain, StreamOrder.order(chain, gone, noon + 1))
    }

    @Test
    fun `without a visitorData the chain is asked as written`() {
        // VISIONOS is refused without one, and the main client's answer is where one is taken from.
        assertEquals(chain, StreamOrder.order(chain, today, noon + 1, hasVisitorData = false))
    }

    @Test
    fun `a refusal dated after now is not believed`() {
        // The clock was wrong when it was noted, or has been set back since. Believing it would
        // keep the client out of first place until the clock caught up.
        val memory = Memory("VISIONOS", mapOf("ANDROID_VR" to noon + 1000))
        assertEquals(chain, StreamOrder.order(chain, memory, noon))
    }

    @Test
    fun `every client is asked once, whatever is remembered`() {
        val names = signedIn + "TVHTML5"
        val times = listOf(null, noon, noon - wait, noon + 5)
        for (worked in names + null) for (vr in times) for (visionos in times) for (ios in times) {
            val refused = listOfNotNull(vr?.let { "ANDROID_VR" to it }, visionos?.let { "VISIONOS" to it }, ios?.let { "IOS" to it })
            val memory = Memory(worked, refused.toMap())
            for (visitor in listOf(true, false)) for (asked in listOf(chain, signedIn)) {
                val order = StreamOrder.order(asked, memory, noon + 1, visitor)
                assertEquals("$memory", asked.sorted(), order.sorted())
                // Only the first place is ever given away: the others keep the order they are written in.
                assertEquals("$memory", asked - order.first(), order.drop(1))
            }
        }
    }

    // What a walk leaves behind.

    @Test
    fun `a walk remembers the client that served and when the ones before it were refused`() {
        val after = StreamOrder.remember(
            Memory(),
            listOf(Asked("ANDROID_VR", worked = false), Asked("VISIONOS", worked = true)),
            noon,
        )
        assertEquals(today, after)
    }

    @Test
    fun `the same client serving again changes nothing, so there is nothing to store`() {
        val after = StreamOrder.remember(today, listOf(Asked("VISIONOS", worked = true)), noon + 5000)
        assertEquals(today, after)
    }

    @Test
    fun `when the client that worked fails, the next one that serves takes its place`() {
        val later = noon + 3 * 60 * 60 * 1000L
        val after = StreamOrder.remember(
            today,
            listOf(Asked("VISIONOS", worked = false), Asked("ANDROID_VR", worked = true)),
            later,
        )
        // ANDROID_VR's old refusal is forgotten with it.
        assertEquals(Memory("ANDROID_VR", mapOf("VISIONOS" to later)), after)
        assertEquals(chain, StreamOrder.order(chain, after, later + 1))
    }

    @Test
    fun `a song no client serves leaves the client that worked last where it was`() {
        val later = noon + 60_000
        val after = StreamOrder.remember(
            today,
            listOf(Asked("VISIONOS", false), Asked("ANDROID_VR", false), Asked("IOS", false)),
            later,
        )
        assertEquals(Memory("VISIONOS", mapOf("ANDROID_VR" to later, "VISIONOS" to later, "IOS" to later)), after)
        assertEquals(listOf("VISIONOS", "ANDROID_VR", "IOS"), StreamOrder.order(chain, after, later + 1))
    }

    @Test
    fun `clients that were not asked keep what was known of them`() {
        val memory = Memory("VISIONOS", mapOf("ANDROID_VR" to noon, "IOS" to noon - 5))
        val after = StreamOrder.remember(memory, listOf(Asked("VISIONOS", worked = true)), noon + wait)
        assertEquals(memory, after)
    }

    @Test
    fun `what the account's client gave is not remembered`() {
        // An age gated song: the three anonymous clients turn it down, the account plays it. The
        // account is asked when the others refuse, and one such song must not put it first.
        val later = noon + 60_000
        val after = StreamOrder.remember(
            today,
            listOf(
                Asked("VISIONOS", false), Asked("ANDROID_VR", false), Asked("IOS", false),
                Asked("ANDROID (account)", worked = true, asAccount = true),
            ),
            later,
        )
        assertEquals("VISIONOS", after.worked)
        assertFalse("ANDROID (account)" in after.refusedAt)
        assertEquals("VISIONOS", StreamOrder.order(signedIn, after, later + 1).first())
        // Nor when it is the one that fails.
        val failed = StreamOrder.remember(today, listOf(Asked("ANDROID (account)", false, asAccount = true)), later)
        assertEquals(today, failed)
    }

    @Test
    fun `a day of listening asks the refused client a few times, not for every song`() {
        // A song every four minutes for a day, ANDROID_VR refused throughout, as on 8 Oct 2026.
        var memory = Memory()
        var refusedRequests = 0
        var songs = 0
        var now = noon
        while (now < noon + 24 * 60 * 60 * 1000L) {
            val asked = mutableListOf<Asked>()
            for (client in StreamOrder.order(chain, memory, now)) {
                val served = client == "VISIONOS"
                asked += Asked(client, served)
                if (!served) refusedRequests++
                if (served) break
            }
            memory = StreamOrder.remember(memory, asked, now)
            songs++
            now += 4 * 60 * 1000L
        }
        assertEquals(360, songs)
        assertEquals(4, refusedRequests)
    }

    @Test
    fun `the day YouTube serves the main client again it goes back to first place by itself`() {
        // Refused until six in the evening, served from then on.
        val servedFrom = noon + 6 * 60 * 60 * 1000L + 1
        var memory = today
        var now = noon
        val firsts = mutableListOf<String>()
        repeat(200) {
            val asked = mutableListOf<Asked>()
            for (client in StreamOrder.order(chain, memory, now)) {
                val served = client == "VISIONOS" || (client == "ANDROID_VR" && now >= servedFrom)
                asked += Asked(client, served)
                if (served) break
            }
            firsts += asked.first().client
            memory = StreamOrder.remember(memory, asked, now)
            now += 4 * 60 * 1000L
        }
        assertEquals("ANDROID_VR", memory.worked)
        assertEquals(emptyMap<String, Long>(), memory.refusedAt)
        // One try at the six hour mark that is refused, the next one six hours later is served.
        assertEquals("VISIONOS", firsts[89])
        assertEquals("ANDROID_VR", firsts[90])
        assertEquals("VISIONOS", firsts[91])
        assertEquals("ANDROID_VR", firsts[180])
        assertEquals("ANDROID_VR", firsts[181])
        assertEquals("ANDROID_VR", firsts.last())
    }

    // Kept between launches.

    @Test
    fun `what is remembered survives being stored`() {
        val memories = listOf(
            Memory(),
            today,
            Memory("IOS", mapOf("ANDROID_VR" to noon, "VISIONOS" to noon + 7)),
            Memory(null, mapOf("ANDROID_VR" to noon)),
        )
        for (memory in memories) assertEquals(memory, StreamOrder.decode(StreamOrder.encode(memory)))
        assertEquals("VISIONOS;ANDROID_VR=$noon", StreamOrder.encode(today))
    }

    @Test
    fun `a stored value that cannot be read is nothing remembered`() {
        for (stored in listOf(null, "", "   ", ";", "=", ";;;=;", "VISIONOS;ANDROID_VR", "a b;c=one", "VISIONOS;ANDROID_VR=soon")) {
            val memory = StreamOrder.decode(stored)
            assertTrue("$stored gave $memory", memory.refusedAt.isEmpty())
        }
        assertEquals(Memory(), StreamOrder.decode("not a client name;x=y"))
        // What can be read of it is kept.
        assertEquals(today, StreamOrder.decode("VISIONOS;ANDROID_VR=$noon;IOS=never;=12;TV HTML=3"))
    }

    // The request that ends a song's wait.

    @Test
    fun `a first request the network did not carry is not asked of the next client`() {
        // Offline, each client fails the same way after the same wait: today the main client's
        // failure ends the song at once, and so does the failure of whichever client goes first.
        assertTrue(StreamOrder.networkDidNotAnswer(UnknownHostException("youtubei.googleapis.com")))
        assertTrue(StreamOrder.networkDidNotAnswer(SocketTimeoutException("timeout")))
        assertTrue(StreamOrder.networkDidNotAnswer(IOException("unexpected end of stream")))
        // YouTube answered, with something this client cannot use: the others are still asked.
        assertFalse(StreamOrder.networkDidNotAnswer(IllegalStateException("Client request invalid: 400")))
        assertFalse(StreamOrder.networkDidNotAnswer(null))
    }

    @Test
    fun `the player asks in this order and says what it asked`() {
        val player = File("src/main/java/com/dd3boh/outertune/utils/YTPlayerUtils.kt").readText()
        assertTrue("the order is not taken from the rule", "StreamOrder.order(" in player)
        assertTrue("what a walk learned is not remembered", "StreamOrder.remember(" in player)
        assertTrue("the chain line was not found", "chain: \$lastStreamTrail" in player)
    }
}
