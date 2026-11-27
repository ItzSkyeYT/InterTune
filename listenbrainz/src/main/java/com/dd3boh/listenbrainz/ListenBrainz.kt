/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.listenbrainz

import com.dd3boh.listenbrainz.models.AdditionalInfo
import com.dd3boh.listenbrainz.models.ErrorResponse
import com.dd3boh.listenbrainz.models.Listen
import com.dd3boh.listenbrainz.models.TokenCheck
import com.dd3boh.listenbrainz.models.TokenResponse
import com.dd3boh.listenbrainz.models.TrackMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * The slice of ListenBrainz's API a music player needs: check a user token, say what is playing,
 * and send listens, one as it finishes or a batch of those kept while they could not go.
 *
 * ListenBrainz has no sign-in for apps. The listener copies the user token from
 * listenbrainz.org/settings and pastes it, and [validateToken] says whose it is. That token opens
 * the whole account, so it is passed per call, goes only in the Authorization header, and never
 * appears in an exception's text, even where ListenBrainz's own answer would quote it.
 *
 * Every request goes through one [Gate]: one at a time, a second apart at least, and none while a
 * rate-limit window ListenBrainz said was spent is still running.
 *
 * Failures come back as [ListenBrainzException]s, sorted by what to do with the listen: keep it
 * for later ([ListenBrainzException.Unreachable], [ListenBrainzException.Unavailable],
 * [ListenBrainzException.RateLimited]), drop it ([ListenBrainzException.Refused]), send nothing
 * more until the listener pastes a token again ([ListenBrainzException.TokenRefused]), or keep it
 * until ListenBrainz takes listens from the account again ([ListenBrainzException.AccountRefused]).
 *
 * @param app who is asking: the User-Agent, and the player and client named in every listen.
 * @param root the API's address. Another server that speaks this API can go here.
 * @param gate keep the default, [Gate.shared], so every caller in the app waits its turn together.
 */
class ListenBrainz(
    private val app: App,
    private val client: HttpClient = defaultClient(),
    root: String = ROOT,
    private val gate: Gate = Gate.shared,
) {
    private val root = root.trimEnd('/')

    /** The newest [playingNow]'s number. An older one still waiting for the gate is not sent. */
    private val newestNowPlaying = AtomicLong()

    /**
     * Who is asking, as ListenBrainz wants it said. The User-Agent is "[name]/[version] ( [contact] )",
     * the form its docs give, and it is required: "Requests without a valid user agent may be
     * blocked without further notice". [name] and [version] are also each listen's media player
     * and submission client. A fork puts its own name and contact here.
     */
    data class App(val name: String, val version: String, val contact: String) {
        val userAgent: String get() = "$name/$version ( $contact )"
    }

    /**
     * Whether ListenBrainz knows [token], and whose it is. A token it does not know is an answer,
     * valid = false, not a failure, and so is an empty one, which is not sent at all. A failure
     * means there was no usable answer: offline, a refusal, or a server in trouble.
     */
    suspend fun validateToken(token: String): Result<TokenCheck> = attempt(token) {
        val key = usable(token) ?: return@attempt TokenCheck(valid = false, userName = null)
        check(key, token)
    }

    /** /1/validate-token's verdict on [key], a token [usable] let through. Fails as [attempt] expects. */
    private suspend fun check(key: String, token: String): TokenCheck {
        val response = gate.pass { client.get("$root/1/validate-token") { identify(key); patient() } }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw refusal(response, text, token)
        val answer = json.decodeFromString(TokenResponse.serializer(), text)
        return if (answer.valid == true) TokenCheck(valid = true, userName = answer.userName)
        else TokenCheck(valid = false, userName = null)
    }

    /**
     * "Playing now", which ListenBrainz shows for the song's length and does not keep. Sent with no
     * time, as ListenBrainz requires, whatever [listen] carries. Allowed to fail and never worth
     * keeping: by the time a connection is back, something else is playing. For the same reason
     * one still waiting for its turn is dropped when a newer one arrives, and reports success.
     */
    suspend fun playingNow(token: String, listen: Listen): Result<Unit> {
        val number = newestNowPlaying.incrementAndGet()
        return submit(token, PLAYING_NOW, listOf(listen.copy(listenedAt = null))) {
            newestNowPlaying.get() == number
        }
    }

    /** A listen just finished. Its [Listen.listenedAt] is when it STARTED. */
    suspend fun single(token: String, listen: Listen): Result<Unit> =
        submit(token, SINGLE, listOf(listen))

    /**
     * Listens kept while they could not be sent, in ONE request: at most [MAX_LISTENS_PER_REQUEST],
     * so split a longer list with [importBatches] first. A batch that breaks a rule is refused here
     * without a request, as ListenBrainz would refuse it. Nothing to send is a success.
     */
    suspend fun import(token: String, listens: List<Listen>): Result<Unit> =
        if (listens.isEmpty()) Result.success(Unit) else submit(token, IMPORT, listens)

    /**
     * A listen as this app sends it, or null when ListenBrainz would refuse it whatever happened:
     * no artist or title once cleaned, a start before October 2002 (a phone whose clock is far
     * off), or more text than a listen may hold.
     *
     * Cleaned the way ListenBrainz would otherwise refuse: NUL characters out ("contains a unicode
     * null" is a refusal), names trimmed, an empty album, a length that is not a positive number
     * of milliseconds up to 24 days and a track number under 1 left out.
     *
     * @param startedAtSeconds when playback started; null for "playing now", which carries no time.
     * @param youTubeId the YouTube video id of a song from YouTube Music, which gives the service
     *   and the link ListenBrainz's own player can play. Null for a local file, which gets neither.
     */
    fun listen(
        artist: String,
        track: String,
        release: String? = null,
        durationMs: Long? = null,
        startedAtSeconds: Long? = null,
        trackNumber: Int? = null,
        youTubeId: String? = null,
    ): Listen? {
        val artistName = clean(artist) ?: return null
        val trackName = clean(track) ?: return null
        if (startedAtSeconds != null && startedAtSeconds < LISTEN_MINIMUM_TS) return null
        val video = youTubeId?.trim()?.takeIf { id -> id.isNotEmpty() && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } }
        val listen = Listen(
            listenedAt = startedAtSeconds,
            trackMetadata = TrackMetadata(
                artistName = artistName,
                trackName = trackName,
                releaseName = release?.let { clean(it) },
                additionalInfo = AdditionalInfo(
                    durationMs = durationMs?.takeIf { it in 1..MAX_DURATION_MS },
                    trackNumber = trackNumber?.takeIf { it > 0 }?.toString(),
                    mediaPlayer = app.name,
                    mediaPlayerVersion = app.version,
                    submissionClient = app.name,
                    submissionClientVersion = app.version,
                    musicService = video?.let { MUSIC_SERVICE_YOUTUBE_MUSIC },
                    originUrl = video?.let { "https://music.youtube.com/watch?v=$it" },
                ),
            ),
        )
        return listen.takeIf { problemWith(it, encode(it), needsTime = false) == null }
    }

    private suspend fun submit(
        token: String,
        type: String,
        listens: List<Listen>,
        stillWanted: () -> Boolean = { true },
    ): Result<Unit> = attempt(token) {
        val key = usable(token) ?: throw ListenBrainzException.TokenRefused(0, NOT_A_TOKEN)
        if (listens.size > MAX_LISTENS_PER_REQUEST) {
            throw ListenBrainzException.Refused(0, "${listens.size} listens in one request, where ListenBrainz takes $MAX_LISTENS_PER_REQUEST")
        }
        val payload = listens.joinToString(",") { listen ->
            val text = encode(listen)
            problemWith(listen, text, needsTime = type != PLAYING_NOW)?.let { throw ListenBrainzException.Refused(0, it) }
            text
        }
        val body = """{"listen_type":"$type","payload":[$payload]}"""
        val response = gate.passIf(stillWanted) {
            client.post("$root/1/submit-listens") {
                identify(key)
                setBody(TextContent(body, ContentType.Application.Json))
            }
        } ?: return@attempt
        if (!response.status.isSuccess()) throw whose(refusal(response, response.bodyAsText(), token), key, token)
    }

    /**
     * ListenBrainz refuses listens with the same 401 for a token it does not know ("Invalid
     * authorization token.") and for an account it takes no listens from: one with no verified
     * email, or a paused one (its server code, read 27 Nov 2026). Only the first wants a new token,
     * and /1/validate-token, which looks up the token and nothing else, tells them apart. With no
     * verdict from it, nothing is decided and the listen is kept, as for a server in trouble.
     */
    private suspend fun whose(refusal: ListenBrainzException, key: String, token: String): ListenBrainzException {
        if (refusal !is ListenBrainzException.TokenRefused) return refusal
        val verdict = attempt(token) { check(key, token) }.getOrElse { e ->
            return e as? ListenBrainzException.RateLimited
                ?: e as? ListenBrainzException.Unreachable
                ?: ListenBrainzException.Unavailable(refusal.status, refusal.message.orEmpty())
        }
        if (!verdict.valid) return refusal
        val said = refusal.message.orEmpty()
        return ListenBrainzException.AccountRefused(refusal.status, said, needsEmail = said.contains("email", ignoreCase = true))
    }

    /**
     * The token check is one question a person waits on, asked once: it waits up to
     * [TOKEN_CHECK_MS] for ListenBrainz, which on a slow day takes 6 to 40 s to answer anything
     * (measured 20 Nov 2026, when the connect dialog gave up at 30 s and said to check the connection).
     */
    private fun HttpRequestBuilder.patient() {
        if (client.pluginOrNull(HttpTimeout) == null) return
        timeout {
            requestTimeoutMillis = TOKEN_CHECK_MS
            socketTimeoutMillis = TOKEN_CHECK_MS
        }
    }

    private fun HttpRequestBuilder.identify(key: String) {
        header(HttpHeaders.Authorization, "Token $key")
        header(HttpHeaders.UserAgent, app.userAgent)
    }

    /** Runs [block], answering every failure as a [ListenBrainzException] whose text is clean of [token]. */
    private suspend fun <T> attempt(token: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ListenBrainzException) {
        Result.failure(e)
    } catch (e: IOException) {
        // No connection, no address, a timeout: nothing was answered, so nothing was refused.
        Result.failure(ListenBrainzException.Unreachable(describe(e, token), slow = answeredTooSlowly(e)))
    } catch (e: SerializationException) {
        Result.failure(ListenBrainzException.Unavailable(200, "ListenBrainz's answer could not be read"))
    } catch (e: Exception) {
        Result.failure(ListenBrainzException.Unavailable(0, describe(e, token)))
    }

    private fun refusal(response: HttpResponse, text: String, token: String): ListenBrainzException {
        val status = response.status.value
        val said = runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { scrub(it, token).take(MESSAGE_LENGTH) }
            ?: "HTTP $status"
        return when (status) {
            // "Invalid authorization token.", and also an account that may not send: no verified
            // email, or paused. To listens, whose() asks validate-token which of the two it is.
            401, 403 -> ListenBrainzException.TokenRefused(status, said)
            429 -> ListenBrainzException.RateLimited(Gate.holdAfter(response), said)
            // The request itself: bad JSON, a field out of bounds, too large. The same request gets
            // the same answer, so it is not worth sending again.
            400, 413 -> ListenBrainzException.Refused(status, said)
            // A server in trouble, or an address that is not ListenBrainz's API (404): not the
            // listen's fault, so it is kept.
            else -> ListenBrainzException.Unavailable(status, said)
        }
    }

    companion object {
        const val ROOT = "https://api.listenbrainz.org"

        /** How long the token check waits for ListenBrainz (see [patient]). Listens keep the client's own 30 s. */
        const val TOKEN_CHECK_MS = 60_000L

        /**
         * Connected, then no answer in time: ListenBrainz slow or overloaded, not the phone offline.
         * A connection that never opened is the phone's side, and says so.
         */
        internal fun answeredTooSlowly(e: Throwable): Boolean = when (e) {
            is ConnectTimeoutException -> false
            is HttpRequestTimeoutException -> true
            is java.net.SocketTimeoutException -> !(e.message ?: "").contains("connect", ignoreCase = true)
            else -> false
        }

        /** YouTube Music's domain in ListenBrainz's table of music services. */
        const val MUSIC_SERVICE_YOUTUBE_MUSIC = "music.youtube.com"

        /** ListenBrainz's MAX_LISTENS_PER_REQUEST. */
        const val MAX_LISTENS_PER_REQUEST = 1000

        /** ListenBrainz's MAX_LISTEN_SIZE, in bytes. */
        const val MAX_LISTEN_SIZE = 10240

        /** ListenBrainz's MAX_LISTEN_PAYLOAD_SIZE, in bytes: MAX_LISTEN_SIZE times MAX_LISTENS_PER_REQUEST. */
        const val MAX_LISTEN_PAYLOAD_SIZE = MAX_LISTEN_SIZE * MAX_LISTENS_PER_REQUEST

        /** ListenBrainz's LISTEN_MINIMUM_TS: 1 Oct 2002, 00:00 UTC. An earlier listened_at is refused. */
        const val LISTEN_MINIMUM_TS = 1033430400L

        /** ListenBrainz's MAX_DURATION_MS_LIMIT: 24 days. */
        const val MAX_DURATION_MS = 2_073_600_000L

        internal const val SINGLE = "single"
        internal const val PLAYING_NOW = "playing_now"
        internal const val IMPORT = "import"

        private const val MESSAGE_LENGTH = 300
        private const val NOT_A_TOKEN = "Not a ListenBrainz token: empty, or with a character no token has"

        /**
         * The most a listen's own JSON may weigh. ListenBrainz checks a request as a whole: at most
         * MAX_LISTEN_PAYLOAD_SIZE bytes, and at most MAX_LISTEN_SIZE bytes for each listen in it,
         * the request's wrapper and commas included. A listen within this, its wrapper's share
         * taken off, passes those checks in any batch of up to MAX_LISTENS_PER_REQUEST.
         */
        internal val LISTEN_BUDGET = MAX_LISTEN_SIZE - """{"listen_type":"$PLAYING_NOW","payload":[]}""".length - 1

        internal val json = Json { ignoreUnknownKeys = true }

        /**
         * Listens kept for later, cut into batches for [import], oldest first as given. At most
         * [most] a batch, which may be lower than ListenBrainz's MAX_LISTENS_PER_REQUEST, so that
         * a batch ListenBrainz refuses costs less to go through one by one. Bytes need no cut of
         * their own: [listen] makes nothing over [LISTEN_BUDGET], and listens within it fit any
         * batch.
         */
        fun importBatches(listens: List<Listen>, most: Int = MAX_LISTENS_PER_REQUEST): List<List<Listen>> {
            require(most in 1..MAX_LISTENS_PER_REQUEST) { "A batch holds 1 to $MAX_LISTENS_PER_REQUEST listens, not $most" }
            return listens.chunked(most)
        }

        /** Why ListenBrainz would refuse [listen] as a finished or saved one, or null if it would not. For whatever keeps listens, before keeping one. */
        fun problemWith(listen: Listen): String? = problemWith(listen, encode(listen), needsTime = true)

        /** The listen's JSON, as it goes in a request and as it can be kept. */
        fun encode(listen: Listen): String = json.encodeToString(Listen.serializer(), listen)

        /** A kept listen back. [encode] of the result gives the same text. */
        fun decode(text: String): Listen = json.decodeFromString(Listen.serializer(), text)

        internal fun problemWith(listen: Listen, encoded: String, needsTime: Boolean): String? {
            val meta = listen.trackMetadata
            val info = meta.additionalInfo
            val texts = listOfNotNull(
                meta.artistName, meta.trackName, meta.releaseName,
                info?.trackNumber, info?.mediaPlayer, info?.mediaPlayerVersion, info?.submissionClient,
                info?.submissionClientVersion, info?.musicService, info?.originUrl,
            )
            val startedAt = listen.listenedAt
            return when {
                meta.artistName.isBlank() -> "No artist"
                meta.trackName.isBlank() -> "No title"
                needsTime && startedAt == null -> "No time it was played"
                needsTime && startedAt != null && startedAt < LISTEN_MINIMUM_TS -> "Played before October 2002 by the phone's clock"
                texts.any { '\u0000' in it } -> "A NUL character, which ListenBrainz refuses"
                encoded.toByteArray(Charsets.UTF_8).size > LISTEN_BUDGET -> "Longer than the $LISTEN_BUDGET bytes a listen may take"
                else -> null
            }
        }

        /**
         * The token as it goes in the header, or null if a header cannot carry it: empty, or with
         * a space, a line break or anything outside printable ASCII. Checked here because the HTTP
         * library's own complaint about a bad header quotes the header, token and all.
         */
        private fun usable(token: String): String? =
            token.trim().takeIf { t -> t.isNotEmpty() && t.all { it in '!'..'~' } }

        private fun clean(text: String): String? = text.replace("\u0000", "").trim().takeIf { it.isNotEmpty() }

        private fun scrub(text: String, token: String): String {
            val secret = token.trim()
            return if (secret.isEmpty()) text else text.replace(secret, "<token>")
        }

        private fun describe(e: Throwable, token: String): String =
            scrub("${e::class.java.simpleName}: ${e.message ?: "no message"}", token).take(MESSAGE_LENGTH)

        fun defaultClient() = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 30_000
            }
        }
    }
}

/**
 * Why a call to ListenBrainz did not work, sorted by what to do with the listen. The text is
 * ListenBrainz's own where it gave one, and never holds the token.
 */
sealed class ListenBrainzException(message: String) : Exception(message) {

    /**
     * 401 or 403: ListenBrainz will not take listens with this token, which it does not know
     * (/1/validate-token says so): wrong, or reset on the settings page ("Invalid authorization
     * token."). Nothing more should go until the listener pastes a token again; what waits is kept.
     * A token it knows on an account that may not send gets [AccountRefused] instead.
     * [status] 0: the token was refused here, never sent, because no header could carry it.
     */
    class TokenRefused(val status: Int, message: String) : ListenBrainzException(message)

    /**
     * 401 or 403 to listens with a token ListenBrainz knows: the account takes no listens for now.
     * Most often it has no verified email ([needsEmail]: "The listens were rejected because your
     * MetaBrainz account does not have a verified email address."), otherwise it is paused, with
     * ListenBrainz's reason as the text. A new token would not help. Keep the listen: it goes once
     * the account is put right on ListenBrainz's side.
     */
    class AccountRefused(val status: Int, message: String, val needsEmail: Boolean) : ListenBrainzException(message)

    /**
     * 429, or a pause ListenBrainz asked for that is longer than [Gate] waits out. Keep the listen
     * and send it after [retryAfterMs]; the gate holds back the next request until then anyway.
     */
    class RateLimited(val retryAfterMs: Long, message: String) : ListenBrainzException(message)

    /**
     * 400 or 413: ListenBrainz refused the request as it is, and will refuse it again. A listen
     * refused alone is dropped; a refused batch holds at least one such listen and is worth
     * sending one by one to find it. [status] 0: refused here without a request, as ListenBrainz
     * would have refused it.
     */
    class Refused(val status: Int, message: String) : ListenBrainzException(message)

    /** 5xx or any other answer that is not ListenBrainz judging the listen: keep it for later. [status] 0 for an error of no HTTP kind. */
    class Unavailable(val status: Int, message: String) : ListenBrainzException(message)

    /**
     * No answer at all: no connection, no address, a timeout. Keep the listen for later. [slow]:
     * the connection opened and ListenBrainz did not answer in time, which is ListenBrainz being
     * slow, not the phone being offline.
     */
    class Unreachable(message: String, val slow: Boolean = false) : ListenBrainzException(message)
}

/**
 * The one way to ListenBrainz. One request at a time, each at least [SPACING_MS] after the last
 * answer (ListenBrainz: "never make more than ONE call per second"), and none before a window
 * ListenBrainz said was spent has run out: X-RateLimit-Remaining at 0, or a 429, both waiting
 * X-RateLimit-Reset-In, the header its docs recommend because it does not trust the phone's clock.
 *
 * Every caller in the app should share one, so the app keeps to the rule however many callers it
 * has: [shared] is that one. A wait longer than [LONGEST_WAIT_MS] is not sat through; the call
 * fails at once with [ListenBrainzException.RateLimited], so nothing hangs for minutes over a
 * song that has long finished.
 */
class Gate internal constructor(
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    constructor() : this(now = { System.nanoTime() / 1_000_000 }, sleep = { delay(it) })

    private val mutex = Mutex()

    /** When the next request may go, on [now]'s clock. Null before the first. */
    private var openAt: Long? = null

    internal suspend fun pass(send: suspend () -> HttpResponse): HttpResponse =
        passIf({ true }, send) ?: error("A request that is always wanted was skipped")

    /** [send] when its turn comes, unless [stillWanted] says no by then. Null when not sent. */
    internal suspend fun passIf(stillWanted: () -> Boolean, send: suspend () -> HttpResponse): HttpResponse? =
        mutex.withLock {
            if (!stillWanted()) return@withLock null
            val wait = openAt?.let { it - now() } ?: 0L
            if (wait > LONGEST_WAIT_MS) {
                throw ListenBrainzException.RateLimited(wait, "ListenBrainz asked for a pause, ${(wait + 999) / 1000} s still to go")
            }
            if (wait > 0) {
                sleep(wait)
                if (!stillWanted()) return@withLock null
            }
            val response = try {
                send()
            } catch (t: Throwable) {
                openAt = now() + SPACING_MS
                throw t
            }
            openAt = now() + holdAfter(response)
            response
        }

    companion object {
        const val SPACING_MS = 1_000L
        const val LONGEST_WAIT_MS = 60_000L

        /** The window brainzutils' rate limiter uses when ListenBrainz names none: 10 s. */
        const val DEFAULT_WINDOW_MS = 10_000L

        val shared = Gate()

        /** How long after [response] the next request must wait. */
        internal fun holdAfter(response: HttpResponse): Long {
            val spent = response.status.value == 429 ||
                response.headers["X-RateLimit-Remaining"]?.trim()?.toLongOrNull()?.let { it <= 0 } == true
            if (!spent) return SPACING_MS
            val resetIn = response.headers["X-RateLimit-Reset-In"].seconds()
            val retryAfter = response.headers[HttpHeaders.RetryAfter].seconds()
            return maxOf(SPACING_MS, resetIn ?: retryAfter ?: DEFAULT_WINDOW_MS)
        }

        private fun String?.seconds(): Long? = this?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}
