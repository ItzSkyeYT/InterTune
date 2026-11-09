/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** One Nostr event (NIP-01), as relays pass them around. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
        put("content", content)
        put("sig", sig)
    }

    /** Whether the id is the hash of the event and the signature is its author's. */
    fun valid(): Boolean = runCatching {
        val hash = Secp256k1.sha256(serialize(pubkey, createdAt, kind, tags, content).toByteArray())
        hash.toHex() == id && Secp256k1.verify(pubkey.hexToBytes(), hash, sig.hexToBytes())
    }.getOrDefault(false)

    companion object {
        /** Builds and signs an event with the session's [secret] key. */
        fun signed(
            secret: ByteArray,
            kind: Int,
            tags: List<List<String>>,
            content: String,
            createdAt: Long = System.currentTimeMillis() / 1000,
        ): NostrEvent {
            val pubkey = Secp256k1.publicKey(secret).toHex()
            val hash = Secp256k1.sha256(serialize(pubkey, createdAt, kind, tags, content).toByteArray())
            return NostrEvent(hash.toHex(), pubkey, createdAt, kind, tags, content, Secp256k1.sign(secret, hash).toHex())
        }

        /** The exact text an event's id is the hash of: `[0,pubkey,created_at,kind,tags,content]`, NIP-01's escaping. */
        internal fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
            buildString {
                append("[0,\"").append(pubkey).append("\",").append(createdAt).append(',').append(kind).append(",[")
                tags.forEachIndexed { i, t ->
                    if (i > 0) append(',')
                    append('[')
                    t.forEachIndexed { j, v ->
                        if (j > 0) append(',')
                        quote(v)
                    }
                    append(']')
                }
                append("],")
                quote(content)
                append(']')
            }

        private fun StringBuilder.quote(s: String) {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        /** The event in a relay's `["EVENT", sub, {...}]`, or null. */
        fun parse(o: JsonObject): NostrEvent? = runCatching {
            NostrEvent(
                id = o["id"]!!.jsonPrimitive.content,
                pubkey = o["pubkey"]!!.jsonPrimitive.content,
                createdAt = o["created_at"]!!.jsonPrimitive.longOrNull ?: return null,
                kind = o["kind"]!!.jsonPrimitive.intOrNull ?: return null,
                tags = o["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
                content = o["content"]!!.jsonPrimitive.content,
                sig = o["sig"]!!.jsonPrimitive.content,
            )
        }.getOrNull()
    }
}

/**
 * Several Nostr relays at once, all carrying one subscription. Every event is sent to every
 * connected relay and each one arriving is handed on once, so a room keeps working while any one
 * relay does. A dropped connection comes back after a growing pause.
 */
class RelayPool(
    private val urls: List<String>,
    private val filter: JsonObject,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())
    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NostrEvent> = _events

    private val _connected = MutableStateFlow(0)
    /** How many relays are connected right now. */
    val connected: StateFlow<Int> = _connected

    @Volatile private var running = false
    private val retries = mutableListOf<Job>()

    fun start() {
        running = true
        urls.forEach { connect(it, 0) }
    }

    fun stop() {
        running = false
        retries.forEach { it.cancel() }
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear()
        _connected.value = 0
    }

    /** Sends [event] to every connected relay; how many it went to. */
    fun publish(event: NostrEvent): Int {
        val text = buildJsonArray { add(JsonPrimitive("EVENT")); add(event.toJson()) }.toString()
        remember(event.id)
        return sockets.values.count { it.send(text) }
    }

    private fun remember(id: String): Boolean = synchronized(seen) {
        if (!seen.add(id)) return false
        if (seen.size > SEEN_KEPT) seen.iterator().run { next(); remove() }
        true
    }

    private fun connect(url: String, attempt: Int) {
        if (!running) return
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets[url] = webSocket
                _connected.value = sockets.size
                webSocket.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(SUB)); add(filter) }.toString())
                Log.i(TAG, "Connected to $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull() ?: return
                if (message.size >= 3 && message[0].jsonPrimitive.content == "EVENT") {
                    val event = (message[2] as? JsonObject)?.let(NostrEvent::parse) ?: return
                    if (remember(event.id)) _events.tryEmit(event)
                } else if (message.isNotEmpty() && message[0].jsonPrimitive.content == "NOTICE") {
                    Log.i(TAG, "$url says: ${message.getOrNull(1)?.jsonPrimitive?.content}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(url, webSocket, attempt)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "$url failed: ${t.message}")
                lost(url, webSocket, attempt)
            }
        })
        if (!running) socket.cancel()
    }

    private fun lost(url: String, socket: WebSocket, attempt: Int) {
        if (sockets[url] === socket) sockets.remove(url)
        _connected.value = sockets.size
        if (!running) return
        retries += scope.launch {
            delay(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
            connect(url, attempt + 1)
        }
    }

    companion object {
        private const val TAG = "RelayPool"
        private const val SUB = "room"
        private const val SEEN_KEPT = 2_000
    }
}
