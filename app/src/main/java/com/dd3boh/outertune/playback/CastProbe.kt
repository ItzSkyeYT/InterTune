/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.cast.CastAddress
import com.dd3boh.outertune.cast.CastFormats
import com.dd3boh.outertune.cast.CastItem
import com.dd3boh.outertune.cast.CastLinks
import com.dd3boh.outertune.cast.CastNetwork
import com.dd3boh.outertune.cast.CastServer
import com.dd3boh.outertune.cast.FCastLink
import com.dd3boh.outertune.cast.FormatChoice
import com.dd3boh.outertune.cast.ReceiverLink
import com.dd3boh.outertune.cast.ReceiverState
import com.dd3boh.outertune.cast.ReceiverStatus
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Casting driven from adb, to try the server and the senders on a device without the screen. Only
 * with Unreleased.CASTING on, and only the shell can use it: the broadcast needs
 * android.permission.DUMP, which the shell holds and no app can.
 *
 *     adb shell am broadcast -a dev.skye.intertune.preview.CAST_PROBE -p dev.skye.intertune.preview \
 *         --es cmd "'cast fcast 10.0.2.2:46899'"
 *
 * The commands:
 * - `serve [id] [aac]` starts the server and logs where a song is served, the current one unless
 *   an id in the queue is given, as it is or as AAC;
 * - `cast <address>` connects to a receiver, written as Add by address takes it (`fcast host:port`,
 *   `chromecast host`, or a DLNA description url), and sends it the current song;
 * - `load [id]` sends another song from the queue;
 * - `play`, `pause`, `stop`, `seek <ms>` and `volume <0 to 1>` pass on to the receiver;
 * - `end` hangs up and stops the server;
 * - `session <address>` casts the queue the way the picker does, through CastSession and the
 *   remote player, and `session end` brings it back to the phone.
 *
 * The commands other than `session` drive a link of their own and share the cast server with a
 * session, so they are refused while one is casting.
 *
 * A song goes in the format the receiver said it plays, and one it fails is tried once more as AAC
 * when it said nothing, the same rules the remote player will follow (CastFormats). Everything
 * done, and every report from the receiver, is logged under CastProbe. The player on the phone is
 * left alone: handing playback over is the remote player's job, not this.
 */
class CastProbe(
    private val service: MusicService,
    private val connectivityManager: ConnectivityManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One command at a time, in the order they came. */
    private val commands = Mutex()

    private var link: ReceiverLink? = null
    private var watch: Job? = null
    private var server: CastServer? = null

    @Volatile
    private var sent: Sent? = null

    /**
     * The last song sent: which load it was on the link, and in what form. A failure is acted on
     * once for each song sent, as the remote player does (SentSong): the receiver keeps saying
     * FAILED until the next song, and sends its whole status again when anything else in it
     * changes, its volume for one.
     */
    private class Sent(val load: Int, val mediaId: String, val mimeType: String, val choice: FormatChoice, val local: Boolean) {
        @Volatile
        var failureTaken = false
    }

    private val action = "${BuildConfig.APPLICATION_ID}.CAST_PROBE"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val command = intent.getStringExtra("cmd")?.trim().orEmpty()
            scope.launch {
                commands.withLock {
                    Log.i(TAG, "> $command")
                    try {
                        run(command)
                    } catch (e: Exception) {
                        Log.w(TAG, "\"$command\" failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun register() {
        if (!Unreleased.CASTING) return
        ContextCompat.registerReceiver(
            service, receiver, IntentFilter(action), Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "Listening for $action")
    }

    fun unregister() {
        runCatching { service.unregisterReceiver(receiver) }
        hangUp()
        scope.cancel()
    }

    private suspend fun run(command: String) {
        val words = command.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val session = service.castSession
        if (words.firstOrNull() == "session") {
            val rest = command.substringAfter(' ', "").trim()
            withContext(Dispatchers.Main) {
                if (rest == "end") {
                    session?.stop()
                } else {
                    val device = CastAddress.parse(rest) ?: error("Not an address: $rest")
                    session?.castTo(device) ?: error("Casting is off")
                }
            }
            return
        }
        if (session != null && session.state.value != CastSession.State.Off) {
            error("A cast session is running; send \"session end\" first")
        }
        when (words.firstOrNull()) {
            "serve" -> {
                val id = words.getOrNull(1)?.takeIf { it != "aac" } ?: currentId()
                val offer = server().prepare(id, aac = "aac" in words.drop(1))
                Log.i(TAG, "Serving $id as ${offer.mimeType}, ${offer.length} bytes, at ${offer.url}")
            }
            "cast" -> {
                val device = CastAddress.parse(command.substringAfter(' ', ""))
                    ?: error("Not an address: ${command.substringAfter(' ', "")}")
                hangUp()
                val network = CastNetwork.wifi(connectivityManager)?.network
                val sender = FCastLink.Sender(Build.MODEL, "InterTune", BuildConfig.VERSION_NAME)
                val link = CastLinks.create(device, scope, sender, network)
                this.link = link
                watch = scope.launch { link.status.collect(::onStatus) }
                link.connect()
                Log.i(TAG, "Connected to ${link.device.name} (${link.device.protocol}), it plays ${link.caps.formats}")
                send(currentId(), aac = null)
            }
            "load" -> send(words.getOrNull(1) ?: currentId(), aac = null)
            "play" -> link().play()
            "pause" -> link().pause()
            "stop" -> link().stop()
            "seek" -> link().seek(words.getOrNull(1)?.toLongOrNull() ?: error("seek takes a position in ms"))
            "volume" -> link().setVolume(words.getOrNull(1)?.toFloatOrNull() ?: error("volume takes 0 to 1"))
            "end" -> hangUp()
            else -> Log.w(TAG, "Unknown command \"$command\"")
        }
    }

    /**
     * Sends [mediaId] to the receiver. With [aac] null, in the form the receiver said it plays;
     * true or false forces AAC or the song as it is.
     */
    private suspend fun send(mediaId: String, aac: Boolean?) {
        val link = link()
        val server = server()
        val song = song(mediaId)
        val local = song.isLocal
        val asIs = server.prepare(mediaId)
        val choice = when (aac) {
            true -> FormatChoice.Aac
            false -> FormatChoice.AsIs
            null -> CastFormats.choose(link.caps.formats, asIs.mimeType, canFetchAac = !local)
        }
        if (choice is FormatChoice.Unplayable) {
            Log.w(TAG, "${link.device.name} plays nothing $mediaId can be sent as (${choice.mimeType})")
            return
        }
        val offer = if (choice == FormatChoice.Aac) server.prepare(mediaId, aac = true) else asIs
        val item = CastItem(
            mediaId = mediaId,
            url = offer.url,
            mimeType = offer.mimeType,
            length = offer.length,
            durationMs = if (song.duration > 0) song.duration * 1000L else -1,
            title = song.title,
            artist = song.artists.joinToString { it.name }.takeIf { it.isNotEmpty() },
            album = song.album?.title,
            artworkUrl = song.thumbnailUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        )
        Log.i(TAG, "Sending $mediaId (${song.title}) as $choice: ${offer.mimeType}, ${offer.length} bytes, ${offer.url}")
        // Noted before the load, since a receiver can refuse the song before load returns. Each
        // load is the next number on the link.
        sent = Sent(link.status.value.load + 1, mediaId, asIs.mimeType, choice, local)
        link.load(item, 0, play = true)
    }

    private fun onStatus(status: ReceiverStatus) {
        Log.i(
            TAG,
            "Receiver: link ${status.link}, ${status.state}, load ${status.load}, ${status.mediaId}, " +
                "${status.positionMs} of ${status.durationMs} ms" + (status.error?.let { ", error: $it" } ?: ""),
        )
        if (status.state != ReceiverState.FAILED) return
        val sent = sent?.takeIf { it.load == status.load && it.mediaId == status.mediaId && !it.failureTaken } ?: return
        sent.failureTaken = true
        val link = link ?: return
        // What the remote player will do: the song is resolved again on its next request, and a
        // receiver that never said what it plays gets one more try as AAC.
        server?.forget(sent.mediaId)
        if (CastFormats.retryAsAac(link.caps.formats, sent.choice, sent.mimeType, canFetchAac = !sent.local)) {
            Log.i(TAG, "Trying ${sent.mediaId} again as AAC")
            scope.launch { commands.withLock { runCatching { send(sent.mediaId, aac = true) }.onFailure { Log.w(TAG, "The AAC retry failed", it) } } }
        }
    }

    private fun hangUp() {
        watch?.cancel()
        watch = null
        link?.close()
        link = null
        sent = null
        server = null
        service.stopCastServer()
    }

    private fun link(): ReceiverLink = link ?: error("Not casting: send \"cast <address>\" first")

    private fun server(): CastServer =
        (service.startCastServer() ?: error("The cast server did not start (no Wi-Fi?)")).also { server = it }

    /** The song now playing on the phone; currentMediaMetadata is safe to read from any thread. */
    private fun currentId(): String = service.currentMediaMetadata.value?.id ?: error("Nothing is playing")

    private fun song(mediaId: String): MediaMetadata =
        service.queueBoard.getCurrentQueue()?.findSong(mediaId) ?: error("$mediaId is not in the queue")

    private companion object {
        const val TAG = "CastProbe"
    }
}
