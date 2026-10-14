/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * What a sender says to a Chromecast, and reads back, inside the CastMessages of [CastChannel]: one
 * JSON object per message, told apart by its namespace and its `type`.
 *
 * Four namespaces are enough to play music:
 * - connection: CONNECT and CLOSE open and close a virtual connection, first to the device itself
 *   (receiver-0) and then to the app playing on it, at the app's transportId;
 * - heartbeat: PING and PONG, both ways, or the device hangs up;
 * - receiver: the device's own status (the apps running, the volume), LAUNCH to start an app,
 *   STOP to end one, and SET_VOLUME;
 * - media: LOAD, PLAY, PAUSE, SEEK, STOP and GET_STATUS, sent to the app, which answers with
 *   MEDIA_STATUS, or with LOAD_FAILED and the like.
 *
 * The app is Google's Default Media Receiver, which every Cast device has built in, so there is
 * nothing to register and nothing of Google's in this app. Times are in seconds, volumes from 0 to
 * 1. A request carries a requestId, which the answer to it carries back; what the device says of
 * its own accord carries 0.
 *
 * As for FCast, the JSON is built and read through kotlinx.serialization's tree, and every reader
 * gives null rather than throwing on a message it cannot make sense of.
 *
 * Written against Google's published Cast message reference and what open senders do: pychromecast
 * (MIT), node-castv2 (MIT) and FUTO's fcast sender SDK (MIT), read for the protocol, not copied.
 */
object Chromecast {
    const val PORT = 8009
    const val SERVICE_TYPE = "_googlecast._tcp"

    /** Google's Default Media Receiver: plays a url with a title and a picture. */
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

    /** Who a sender is on the device's own connection, and who the device is on it. */
    const val PLATFORM_SENDER = "sender-0"
    const val PLATFORM_RECEIVER = "receiver-0"

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    /**
     * InterTune's own receiver passes on here what it is asked of the phone: Next and Previous,
     * repeat and shuffle, pressed on the TV's remote or in Google Home (RemoteRequest). Google's
     * own receiver never speaks it, so with that one nothing arrives here.
     */
    const val NS_INTERTUNE = "urn:x-cast:dev.skye.intertune"

    const val CONNECT = "CONNECT"
    const val CLOSE = "CLOSE"
    const val PING = "PING"
    const val PONG = "PONG"
    const val GET_STATUS = "GET_STATUS"
    const val RECEIVER_STATUS = "RECEIVER_STATUS"
    const val LAUNCH = "LAUNCH"
    const val STOP = "STOP"
    const val SET_VOLUME = "SET_VOLUME"
    const val LOAD = "LOAD"
    const val PLAY = "PLAY"
    const val PAUSE = "PAUSE"
    const val SEEK = "SEEK"
    const val MEDIA_STATUS = "MEDIA_STATUS"

    const val LOAD_FAILED = "LOAD_FAILED"
    const val LOAD_CANCELLED = "LOAD_CANCELLED"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_PLAYER_STATE = "INVALID_PLAYER_STATE"
    const val LAUNCH_ERROR = "LAUNCH_ERROR"

    /** The generic failure newer receivers send for a request, with a detailedErrorCode. */
    const val ERROR = "ERROR"

    const val PLAYER_IDLE = "IDLE"
    const val PLAYER_PLAYING = "PLAYING"
    const val PLAYER_PAUSED = "PAUSED"
    const val PLAYER_BUFFERING = "BUFFERING"
    const val PLAYER_LOADING = "LOADING"

    const val IDLE_FINISHED = "FINISHED"
    const val IDLE_CANCELLED = "CANCELLED"
    const val IDLE_INTERRUPTED = "INTERRUPTED"
    const val IDLE_ERROR = "ERROR"

    /** MusicTrackMediaMetadata, which has an artist and an album where the generic kind has neither. */
    const val METADATA_MUSIC_TRACK = 3

    // What the sender sends

    /**
     * Opens a virtual connection. connType 0 is a strong connection, the kind a sender that controls
     * playback holds; origin and userAgent are what Chrome sends, and some firmware logs them.
     */
    fun connect(userAgent: String): String = buildJsonObject {
        put("type", CONNECT)
        put("origin", buildJsonObject {})
        put("userAgent", userAgent)
        put("connType", 0)
    }.toString()

    fun close(): String = typeOnly(CLOSE)

    fun ping(): String = typeOnly(PING)

    fun pong(): String = typeOnly(PONG)

    /** GET_STATUS, on the receiver namespace for the device's status or the media one for the song's. */
    fun getStatus(requestId: Int): String = buildJsonObject {
        put("type", GET_STATUS)
        put("requestId", requestId)
    }.toString()

    fun launch(requestId: Int, appId: String = DEFAULT_MEDIA_RECEIVER): String = buildJsonObject {
        put("type", LAUNCH)
        put("requestId", requestId)
        put("appId", appId)
    }.toString()

    /** Ends the app of [sessionId], on the receiver namespace. */
    fun stopApp(requestId: Int, sessionId: String): String = buildJsonObject {
        put("type", STOP)
        put("requestId", requestId)
        put("sessionId", sessionId)
    }.toString()

    /** The device's volume, from 0 to 1, or its mute; at least one of the two. */
    fun setVolume(requestId: Int, level: Float? = null, muted: Boolean? = null): String = buildJsonObject {
        require(level != null || muted != null) { "Neither a level nor a mute" }
        put("type", SET_VOLUME)
        put("requestId", requestId)
        put("volume", buildJsonObject {
            if (level != null) put("level", volumeOf(level))
            if (muted != null) put("muted", muted)
        })
    }.toString()

    /**
     * LOAD for one song at [url], starting at [startMs], playing at once or held paused there.
     *
     * The url goes as contentId, which older receivers play, and as contentUrl, which the current
     * receiver framework plays when it is there. BUFFERED says it is a file with an end, not a live
     * stream, so the receiver shows a length and allows seeking.
     */
    fun load(requestId: Int, sessionId: String?, item: CastItem, url: String, startMs: Long, autoplay: Boolean): String = buildJsonObject {
        put("type", LOAD)
        put("requestId", requestId)
        if (sessionId != null) put("sessionId", sessionId)
        put("media", buildJsonObject {
            put("contentId", url)
            put("contentUrl", url)
            put("streamType", "BUFFERED")
            put("contentType", CastMime.bare(item.mimeType))
            if (item.durationMs > 0) put("duration", item.durationMs / 1000.0)
            put("metadata", buildJsonObject {
                put("metadataType", METADATA_MUSIC_TRACK)
                item.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                item.artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
                item.album?.takeIf { it.isNotBlank() }?.let { put("albumName", it) }
                item.artworkUrl?.let { art ->
                    put("images", buildJsonArray { add(buildJsonObject { put("url", art) }) })
                }
            })
        })
        put("currentTime", startMs.coerceAtLeast(0) / 1000.0)
        put("autoplay", autoplay)
    }.toString()

    /** PLAY, PAUSE or STOP for the media session [mediaSessionId]. */
    fun mediaCommand(type: String, requestId: Int, mediaSessionId: Long): String = buildJsonObject {
        put("type", type)
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
    }.toString()

    /** SEEK without a resumeState, so the song goes on playing or stays paused as it was. */
    fun seek(requestId: Int, mediaSessionId: Long, positionMs: Long): String = buildJsonObject {
        put("type", SEEK)
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
    }.toString()

    private fun typeOnly(type: String) = buildJsonObject { put("type", type) }.toString()

    /** A volume from 0 to 1 in three decimals, not the float's 0.30000001192092896. */
    private fun volumeOf(value: Float): Double = (value.coerceIn(0f, 1f) * 1000).roundToInt() / 1000.0

    // What the device sends

    /** One message's JSON, its type and its requestId (0 when it answers nothing). */
    class Payload(val type: String?, val requestId: Int, val body: JsonObject)

    /** An app running on the device, from RECEIVER_STATUS. */
    data class App(
        val appId: String?,
        val sessionId: String?,
        val transportId: String?,
        val displayName: String?,
        /** The screensaver-like backdrop a device shows when nothing is cast, which nobody is using. */
        val isIdleScreen: Boolean,
    )

    /**
     * The device's status. [level] is its volume from 0 to 1 and [fixedVolume] whether that cannot
     * be changed from a sender, as on a TV set to control volume only from its own remote.
     */
    data class Device(val apps: List<App>, val level: Float?, val muted: Boolean?, val fixedVolume: Boolean)

    /** One media session in a MEDIA_STATUS. Times in milliseconds, null when the device left them out. */
    data class Media(
        val mediaSessionId: Long,
        val playerState: String?,
        val idleReason: String?,
        val positionMs: Long?,
        val durationMs: Long?,
        val speed: Float?,
        val contentId: String?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * What [payload], said on [NS_INTERTUNE], asks of the phone, or null for anything else. Repeat
     * comes in the Cast protocol's own words, as the receiver framework hands them over.
     */
    fun request(payload: Payload): RemoteRequest? = when (payload.type) {
        "NEXT" -> RemoteRequest.Next
        "PREVIOUS" -> RemoteRequest.Previous
        "REPEAT" -> when ((payload.body["mode"] as? JsonPrimitive)?.content) {
            "REPEAT_OFF" -> RemoteRequest.Repeat(RemoteRepeat.OFF)
            "REPEAT_SINGLE" -> RemoteRequest.Repeat(RemoteRepeat.ONE)
            "REPEAT_ALL", "REPEAT_ALL_AND_SHUFFLE" -> RemoteRequest.Repeat(RemoteRepeat.ALL)
            else -> null
        }
        "SHUFFLE" -> (payload.body["on"] as? JsonPrimitive)?.booleanOrNull?.let { RemoteRequest.Shuffle(it) }
        else -> null
    }

    fun parse(payload: String?): Payload? {
        val body = payload?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        return Payload(body.text("type"), body.number("requestId")?.toInt() ?: 0, body)
    }

    fun parseDevice(body: JsonObject): Device? {
        val status = body["status"].asObject() ?: return null
        val apps = (status["applications"] as? JsonArray).orEmpty().mapNotNull { element ->
            val app = element.asObject() ?: return@mapNotNull null
            App(
                appId = app.text("appId"),
                sessionId = app.text("sessionId"),
                transportId = app.text("transportId"),
                displayName = app.text("displayName"),
                isIdleScreen = app.flag("isIdleScreen") == true,
            )
        }
        val volume = status["volume"].asObject()
        return Device(
            apps = apps,
            level = volume?.number("level")?.toFloat()?.coerceIn(0f, 1f),
            muted = volume?.flag("muted"),
            fixedVolume = volume?.text("controlType").equals("fixed", ignoreCase = true),
        )
    }

    /** The media sessions in a MEDIA_STATUS; none when the app holds none, as after a stop. */
    fun parseMedia(body: JsonObject): List<Media> = (body["status"] as? JsonArray).orEmpty().mapNotNull { element ->
        val status = element.asObject() ?: return@mapNotNull null
        val id = status.number("mediaSessionId")?.toLong() ?: return@mapNotNull null
        val media = status["media"].asObject()
        Media(
            mediaSessionId = id,
            playerState = status.text("playerState")?.uppercase(),
            idleReason = status.text("idleReason")?.uppercase(),
            positionMs = seconds(status.number("currentTime")),
            durationMs = seconds(media?.number("duration"))?.takeIf { it > 0 },
            speed = status.number("playbackRate")?.toFloat()?.takeIf { it > 0f },
            contentId = media?.text("contentUrl") ?: media?.text("contentId"),
        )
    }

    /** Why a request failed, as far as the device said: its reason, its error code, or both. */
    fun reasonOf(body: JsonObject): String? {
        val reason = body.text("reason")?.takeIf { it.isNotBlank() }
        val code = body.number("detailedErrorCode")?.toInt()
        return when {
            reason != null && code != null -> "$reason, $code"
            reason != null -> reason
            code != null -> "error $code"
            else -> null
        }
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun seconds(value: Double?): Long? = value?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }
}
