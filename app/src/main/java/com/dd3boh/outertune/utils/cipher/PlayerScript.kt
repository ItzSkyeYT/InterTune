/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import com.zionhuang.innertube.models.YouTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** YouTube's player script as it was fetched, with what the app reads out of it itself. */
class PlayerScript(
    /** The name YouTube gives this build of its player. */
    val id: String,
    val text: String,
    /**
     * Read from [text]. A web client's request carries it, and YouTube then ciphers the answer
     * for the player it belongs to: so the script an answer goes with is this one, by
     * construction, and never one fetched at another moment.
     */
    val signatureTimestamp: Int,
)

/**
 * Finds YouTube's player script, and keeps it.
 *
 * The app has read one thing from this script for a long time, the signature timestamp, through
 * NewPipeExtractor (YoutubeJavaScriptPlayerManager.getSignatureTimestamp). NewPipeExtractor
 * fetches the script for that and keeps the text to itself: the field is private and the class
 * that does the fetching (YoutubeJavaScriptExtractor) is not public, in v0.26.3 as in v0.26.5. So
 * the text cannot be had from it, and a solver needs the text. This fetches it the way
 * NewPipeExtractor does, from the same two addresses, and reads the timestamp with the same
 * pattern, so the app asks YouTube nothing it was not asking already:
 * - [IFRAME_API], a few hundred bytes of script that name the current player;
 * - the player itself, [address], about two and a half megabytes.
 *
 * The page is asked again every [REFRESH_MS], and the script only when the page names another
 * player. A script that cannot be had, or has no timestamp in it, leaves the one before in place
 * and is not asked for again for [RETRY_MS]: YouTube answers an older timestamp for a good while,
 * and a failing fetch must not become two requests for every song.
 *
 * [fetch] gives the text at an address, or null. Handed in, so the tests have no network.
 *
 * With [files] the script outlives the process: a new process takes up the one kept, with the
 * time the page was last asked, so that within [REFRESH_MS] of it nothing is fetched at all, and
 * after it only the page, unless that names another player.
 */
class PlayerScripts(
    private val fetch: (String) -> String?,
    private val files: SolverFiles? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var kept: PlayerScript? = null
    private var askedAt = 0L
    private var failedAt: Long? = null
    private var filesRead = false

    /** What [files] holds of an earlier run, taken up once. A script with no timestamp in it is not one. */
    private fun takeUpWhatWasKept() {
        if (filesRead) return
        filesRead = true
        val current = files?.current() ?: return
        val text = files.script(current.id) ?: return
        val timestamp = signatureTimestampIn(text) ?: return
        kept = PlayerScript(current.id, text, timestamp)
        askedAt = current.seenAt
    }

    /** The script to ask a web client with and to solve its address against, or null when there is none to be had. */
    suspend fun current(): PlayerScript? = mutex.withLock {
        val time = now()
        if (kept == null) runInterruptible(Dispatchers.IO) { takeUpWhatWasKept() }
        kept?.let { if (time - askedAt in 0 until REFRESH_MS) return@withLock it }
        failedAt?.let { if (time - it in 0 until RETRY_MS) return@withLock kept }
        val found = try {
            // Interruptible, so that whoever set a time limit on this gets its thread back.
            runInterruptible(Dispatchers.IO) { fetched(kept) }
        } catch (givenUpOn: CancellationException) {
            // Out of time counts as a failure: the next song does not wait the same time over.
            failedAt = time
            throw givenUpOn
        }
        if (found == null) {
            failedAt = time
            return@withLock kept
        }
        failedAt = null
        askedAt = time
        kept = found
        found
    }

    private fun fetched(previous: PlayerScript?): PlayerScript? {
        val time = now()
        val id = fetch(IFRAME_API)?.let { idIn(it) } ?: return null
        if (previous?.id == id) return previous.also { files?.seen(id, time) }
        val text = fetch(address(id)) ?: return null
        val timestamp = signatureTimestampIn(text) ?: return null
        files?.keepScript(id, text, time)
        return PlayerScript(id, text, timestamp)
    }

    companion object {
        const val IFRAME_API = "https://www.youtube.com/iframe_api"

        /** Six hours, as the other things the stream chain keeps: see StreamOrder.RETRY_REFUSED_MS. */
        const val REFRESH_MS = 6 * 60 * 60 * 1000L

        const val RETRY_MS = 60 * 1000L

        /** A player script is about two and a half megabytes. Anything much larger is not one. */
        private const val MAX_BYTES = 16L * 1024 * 1024

        /** In the page the slashes are written with a backslash before them, and the name stands between two of them. */
        private val PLAYER_ID = Regex("""player\\/([A-Za-z0-9_-]{8,16})\\/""")
        private val SIGNATURE_TIMESTAMP = Regex("""signatureTimestamp[=:](\d+)""")

        /** The player [page] names, or null. Only ever a short name: an address is built from it. */
        fun idIn(page: String): String? = PLAYER_ID.find(page)?.groupValues?.get(1)

        /** Where the script of player [id] is, as NewPipeExtractor asks for it. */
        fun address(id: String): String = "https://www.youtube.com/s/player/$id/player_ias.vflset/en_GB/base.js"

        fun signatureTimestampIn(script: String): Int? =
            SIGNATURE_TIMESTAMP.find(script)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        /**
         * A [fetch] over [client]: the text at an address when YouTube answers with one, and null
         * for anything else, a failure of the connection included. Asked as the browser
         * NewPipeExtractor's own requests say they are.
         */
        fun over(client: OkHttpClient): (String) -> String? = { url ->
            runCatching {
                val request = Request.Builder().url(url).header("User-Agent", YouTubeClient.USER_AGENT_WEB).build()
                client.newCall(request).execute().use { answer ->
                    val body = answer.body
                    if (!answer.isSuccessful || body == null) return@use null
                    val source = body.source()
                    source.request(MAX_BYTES + 1)
                    if (source.buffer.size > MAX_BYTES) null else source.readString(Charsets.UTF_8)
                }
            }.getOrNull()
        }
    }
}
