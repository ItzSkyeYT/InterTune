/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** A receiver broke the FCast framing: a size out of bounds, or a frame cut short. */
class FCastProtocolException(message: String) : IOException(message)

/**
 * FCast v3 on the wire (https://docs.fcast.org/protocol/v3/), and the few messages a music sender
 * needs.
 *
 * Every frame is a 4-byte little-endian size, one opcode byte and a UTF-8 JSON body; the size counts
 * the opcode and the body but not itself. Times are in seconds, volumes from 0 to 1. The JSON is
 * built and read by hand through kotlinx.serialization's tree, since the app module has no
 * serialization plugin, and every reader here gives null rather than throwing on a body it cannot
 * make sense of: a receiver's odd message is ignored, never the end of the connection.
 *
 * Written against the protocol page and the two receivers FUTO publishes: the Android one (Kotlin,
 * receivers/android, speaks v3) and the desktop one (Rust, crates/receiver-core, v1 to v4).
 */
object FCast {
    const val PORT = 46899
    const val SERVICE_TYPE = "_fcast._tcp"

    /** The version this sender speaks. v4, over TLS and with FlatBuffers, is for later. */
    const val VERSION = 3L

    /**
     * The largest size field sent: the Android receiver drops the connection above 32000, which is
     * less than the 32 KB the protocol page names.
     */
    const val MAX_SEND = 32000

    /** The largest size field read. Receivers only ever send short messages; this is generous. */
    const val MAX_READ = 64 * 1024

    const val PLAY = 1
    const val PAUSE = 2
    const val RESUME = 3
    const val STOP = 4
    const val SEEK = 5
    const val PLAYBACK_UPDATE = 6
    const val VOLUME_UPDATE = 7
    const val SET_VOLUME = 8
    const val PLAYBACK_ERROR = 9
    const val SET_SPEED = 10
    const val VERSION_MESSAGE = 11
    const val PING = 12
    const val PONG = 13
    const val INITIAL = 14
    const val PLAY_UPDATE = 15
    const val SET_PLAYLIST_ITEM = 16
    const val SUBSCRIBE_EVENT = 17
    const val UNSUBSCRIBE_EVENT = 18
    const val EVENT = 19

    const val STATE_IDLE = 0
    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2

    const val EVENT_MEDIA_ITEM_START = 0
    const val EVENT_MEDIA_ITEM_END = 1

    /** The highest opcode a receiver of each version knows; anything above it is not sent. */
    fun highestOpcode(version: Long): Int = when {
        version <= 1 -> SET_VOLUME
        version == 2L -> PONG
        else -> EVENT
    }

    class Frame(val opcode: Int, val body: String?) {
        override fun toString() = "Frame($opcode, $body)"
    }

    // Framing

    fun encode(opcode: Int, body: String? = null): ByteArray {
        val data = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val size = 1 + data.size
        require(size <= MAX_SEND) { "An FCast message of $size bytes is over the receivers' limit" }
        val frame = ByteArray(4 + size)
        frame[0] = (size and 0xFF).toByte()
        frame[1] = (size shr 8 and 0xFF).toByte()
        frame[2] = (size shr 16 and 0xFF).toByte()
        frame[3] = (size shr 24 and 0xFF).toByte()
        frame[4] = opcode.toByte()
        data.copyInto(frame, 5)
        return frame
    }

    /**
     * Reads the next frame, blocking, or null when the receiver closed the connection between two
     * frames. A connection closed inside a frame, or a size that is 0 or past [MAX_READ], throws.
     */
    fun read(input: InputStream): Frame? {
        val header = ByteArray(4)
        val first = input.read(header, 0, 4)
        if (first < 0) return null
        readFully(input, header, first, 4 - first)
        val size = (header[0].toLong() and 0xFF) or
            ((header[1].toLong() and 0xFF) shl 8) or
            ((header[2].toLong() and 0xFF) shl 16) or
            ((header[3].toLong() and 0xFF) shl 24)
        if (size < 1 || size > MAX_READ) throw FCastProtocolException("A frame of $size bytes")
        val packet = ByteArray(size.toInt())
        readFully(input, packet, 0, packet.size)
        val body = if (packet.size > 1) String(packet, 1, packet.size - 1, Charsets.UTF_8) else null
        return Frame(packet[0].toInt() and 0xFF, body)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val count = input.read(buffer, offset + done, length - done)
            if (count < 0) throw EOFException("The receiver closed inside a frame")
            done += count
        }
    }

    // What the sender sends

    fun versionBody(version: Long = VERSION): String = buildJsonObject { put("version", version) }.toString()

    fun initialBody(displayName: String, appName: String, appVersion: String): String = buildJsonObject {
        put("displayName", displayName)
        put("appName", appName)
        put("appVersion", appVersion)
    }.toString()

    /**
     * The Play message for one song at [url], starting at [startMs], in the fields a receiver of
     * [version] knows.
     *
     * v3 has a title and a picture but no artist, so the artist goes after the title, which is what
     * the Android receiver shows. `custom` is left out on purpose: the Android receiver reads it as
     * a contextual value it has no serializer for, and a Play it cannot read is a Play ignored.
     */
    fun playBody(item: CastItem, url: String, startMs: Long, version: Long, volume: Float? = null): String = buildJsonObject {
        put("container", CastMime.bare(item.mimeType))
        put("url", url)
        put("time", startMs.coerceAtLeast(0) / 1000.0)
        if (version >= 2) put("speed", 1.0)
        if (version >= 3) {
            if (volume != null) put("volume", volumeOf(volume))
            val title = listOfNotNull(item.title?.takeIf { it.isNotBlank() }, item.artist?.takeIf { it.isNotBlank() })
                .joinToString(" - ")
            if (title.isNotEmpty() || item.artworkUrl != null) {
                put("metadata", buildJsonObject {
                    put("type", 0)
                    if (title.isNotEmpty()) put("title", title)
                    item.artworkUrl?.let { put("thumbnailUrl", it) }
                })
            }
        }
    }.toString()

    fun seekBody(positionMs: Long): String = buildJsonObject { put("time", positionMs.coerceAtLeast(0) / 1000.0) }.toString()

    fun setVolumeBody(level: Float): String = buildJsonObject { put("volume", volumeOf(level)) }.toString()

    /** A volume from 0 to 1 in three decimals, not the float's 0.30000001192092896. */
    private fun volumeOf(value: Float): Double = (value.coerceIn(0f, 1f) * 1000).roundToInt() / 1000.0

    fun setSpeedBody(speed: Float): String = buildJsonObject { put("speed", speed.toDouble()) }.toString()

    fun subscribeBody(eventType: Int): String = buildJsonObject {
        put("event", buildJsonObject { put("type", eventType) })
    }.toString()

    // What the receiver sends

    /** A receiver's PlaybackUpdate. Times in milliseconds, each null when the receiver left it out. */
    data class PlaybackUpdate(
        val state: Int,
        val positionMs: Long?,
        val durationMs: Long?,
        val speed: Float?,
        val generationTime: Long?,
    )

    data class ReceiverInfo(val displayName: String?, val appName: String?, val appVersion: String?)

    /** An Event: its type, and the url of the item it is about when it names one. */
    data class Event(val type: Int, val url: String?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun objectOf(body: String?): JsonObject? =
        body?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun seconds(value: Double?): Long? = value?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }

    fun parseVersion(body: String?): Long? =
        objectOf(body)?.let { (it["version"] as? JsonPrimitive)?.longOrNull }?.takeIf { it > 0 }

    fun parsePlaybackUpdate(body: String?): PlaybackUpdate? {
        val message = objectOf(body) ?: return null
        val state = message.number("state")?.toInt() ?: return null
        return PlaybackUpdate(
            state = state,
            positionMs = seconds(message.number("time")),
            durationMs = seconds(message.number("duration"))?.takeIf { it > 0 },
            speed = message.number("speed")?.toFloat()?.takeIf { it > 0f },
            generationTime = message.number("generationTime")?.toLong(),
        )
    }

    fun parseVolume(body: String?): Float? =
        objectOf(body)?.number("volume")?.toFloat()?.coerceIn(0f, 1f)

    /** The error's message, or a plain one when the receiver sent none that can be read. */
    fun parseError(body: String?): String =
        objectOf(body)?.text("message")?.takeIf { it.isNotBlank() } ?: "The receiver could not play it"

    fun parseInitial(body: String?): ReceiverInfo? = objectOf(body)?.let {
        ReceiverInfo(it.text("displayName"), it.text("appName"), it.text("appVersion"))
    }

    fun parseEvent(body: String?): Event? {
        val event = objectOf(body)?.get("event").asObject() ?: return null
        val type = event.number("type")?.toInt() ?: return null
        return Event(type, event["item"].asObject()?.text("url"))
    }

    /** The url of what another sender just played on the receiver, from a PlayUpdate. */
    fun parsePlayUpdateUrl(body: String?): String? =
        objectOf(body)?.get("playData").asObject()?.text("url")
}
