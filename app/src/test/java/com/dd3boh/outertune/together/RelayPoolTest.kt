/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Leaving a room closed the relays already connected, but one still connecting was not among them: it opened a moment
 * later, asked for the room, and stayed open with nothing to end it, receiving the room's every word and showing the
 * relay this phone. And the reconnects waiting were kept in a plain list, filled from OkHttp's threads and emptied on
 * the main one, one more for every drop as long as the room lasted (review of 0.13, 18 Nov 2026).
 */
class RelayPoolTest {

    private class FakeSocket(private val request: Request) : WebSocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            if (closed) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?): Boolean = !closed.also { closed = true }
        override fun cancel() {
            closed = true
        }
    }

    /** Every connection the pool asks for, in order, with the listener OkHttp would call. */
    private class Relays : WebSocket.Factory {
        val made = mutableListOf<Pair<FakeSocket, WebSocketListener>>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeSocket(request).also { made += it to listener }
    }

    private fun opened(socket: FakeSocket) =
        Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()

    private val event = NostrEvent.signed(Secp256k1.newSecretKey(), TogetherMessage.KIND, listOf(listOf("t", "tag")), "sealed")

    @Test
    fun `a relay that opens after the room stopped is closed, not kept`() {
        val relays = Relays()
        val job = SupervisorJob()
        val pool = RelayPool(listOf("wss://one.example", "wss://two.example"), buildJsonObject {}, relays, CoroutineScope(job + Dispatchers.Default))
        pool.start()
        assertEquals(2, relays.made.size)
        val (first, firstListener) = relays.made[0]
        firstListener.onOpen(first, opened(first))
        assertEquals(1, pool.connected.value)
        assertTrue(first.sent.single().startsWith("[\"REQ\""))
        // the room stops while the second relay is still connecting
        pool.stop()
        assertTrue(first.closed)
        val (late, lateListener) = relays.made[1]
        lateListener.onOpen(late, opened(late))
        assertTrue("a relay that opened after the stop is left open", late.closed)
        assertEquals("it asked the relay for the room", emptyList<String>(), late.sent)
        assertEquals(0, pool.connected.value)
        assertEquals(0, pool.publish(event))
        job.cancel()
    }

    @Test
    fun `a dropped relay has one reconnect waiting, and the stop ends it`() {
        val relays = Relays()
        val job = SupervisorJob()
        val pool = RelayPool(listOf("wss://one.example"), buildJsonObject {}, relays, CoroutineScope(job + Dispatchers.Default))
        pool.start()
        val (socket, listener) = relays.made.single()
        listener.onOpen(socket, opened(socket))
        // however often it is reported lost, one reconnect waits for it
        repeat(3) { listener.onFailure(socket, IOException("gone"), null) }
        assertEquals(0, pool.connected.value)
        assertEquals(1, job.children.count { it.isActive })
        pool.stop()
        assertEquals("the stop left a reconnect waiting", 0, job.children.count { it.isActive })
        Thread.sleep(1_300)  // past the first reconnect's second
        assertEquals("a relay was asked again after the stop", 1, relays.made.size)
        job.cancel()
    }

    @Test
    fun `only events their author signed are taken, each once`() {
        val relays = Relays()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val pool = RelayPool(listOf("wss://one.example"), buildJsonObject {}, relays, scope)
        val got = Collections.synchronizedList(mutableListOf<NostrEvent>())
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.events.collect { got += it } }
        pool.start()
        val (socket, listener) = relays.made.single()
        listener.onOpen(socket, opened(socket))
        fun relayed(e: NostrEvent) = listener.onMessage(socket, buildJsonArray { add(JsonPrimitive("EVENT")); add(JsonPrimitive("room")); add(e.toJson()) }.toString())

        val word = NostrEvent.signed(Secp256k1.newSecretKey(), TogetherMessage.KIND, listOf(listOf("t", "tag")), "sealed")
        // a relay's forgeries: the same id over other content, a new time with the id made again but the old signature,
        // and the word passed off as someone else's
        val forgedSameId = word.copy(content = "other")
        val later = word.createdAt + 60
        val newId = Secp256k1.sha256(NostrEvent.serialize(word.pubkey, later, word.kind, word.tags, word.content).toByteArray()).toHex()
        val replayed = word.copy(id = newId, createdAt = later)
        val other = Secp256k1.publicKey(Secp256k1.newSecretKey()).toHex()
        val otherKey = word.copy(
            pubkey = other,
            id = Secp256k1.sha256(NostrEvent.serialize(other, word.createdAt, word.kind, word.tags, word.content).toByteArray()).toHex(),
        )
        for (forged in listOf(forgedSameId, replayed, otherKey)) relayed(forged)
        assertEquals("a forged event was taken", emptyList<NostrEvent>(), got.toList())
        // the real one still comes through after a forgery under its id, and once however many relays pass it on
        relayed(word)
        relayed(word)
        assertEquals(listOf(word), got.toList())
        pool.stop()
        job.cancel()
    }

    @Test
    fun `the reconnects are kept where OkHttp's threads and the main one can both reach them`() {
        val nostr = File("src/main/java/com/dd3boh/outertune/together/Nostr.kt").readText()
        assertTrue("private val retries = ConcurrentHashMap<String, Job>()" in nostr)
        assertTrue("retries.put(url, scope.launch {" in nostr)
        assertFalse("retries +=" in nostr)
        assertFalse("mutableListOf<Job>()" in nostr)
    }
}
