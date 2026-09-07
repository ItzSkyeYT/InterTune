package com.dd3boh.lastfm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The slice of the Last.fm API a music player needs: log in, say what is playing, and scrobble.
 *
 * Auth is the browser flow, not the one that takes a username and password. The app asks for a
 * token, sends the user to last.fm to approve it, and trades the approved token for a session key
 * that does not expire. InterTune therefore never sees, handles or stores a Last.fm password, which
 * is the only version of this worth shipping in an app people sideload.
 *
 * @param apiKey    from last.fm/api/account/create
 * @param apiSecret from the same page. Used only to sign requests locally; it is never sent.
 */
class LastFm(
    private val apiKey: String,
    private val apiSecret: String,
    private val client: HttpClient = defaultClient(),
) {

    /** Step one of login: a token to be approved. */
    suspend fun requestToken(): Result<String> = runCatching {
        val body = client.get(ROOT) {
            parameter("method", "auth.getToken")
            parameter("api_key", apiKey)
            parameter("format", "json")
        }.body<TokenResponse>()
        // Last.fm answers a rejected key with HTTP 200 and an error body. Read it, or the caller
        // gets "Field 'token' is required", which points at our parser rather than at the real
        // cause, which is usually a missing or wrong API key.
        body.error?.let { throw LastFmException(it, body.message ?: "no message") }
        body.token ?: throw LastFmException(0, "Last.fm returned neither a token nor an error")
    }

    /** Step two: where to send the user so they can approve that token. */
    fun authorizeUrl(token: String) = "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    /**
     * Step three, after the user has approved. The session key it returns does not expire, so it is
     * stored once and reused; there is no refresh to get wrong.
     */
    suspend fun session(token: String): Result<Session> = runCatching {
        val params = mapOf("api_key" to apiKey, "method" to "auth.getSession", "token" to token)
        val body = client.get(ROOT) {
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("api_sig", sign(params))
            parameter("format", "json")
        }.body<SessionResponse>()
        body.error?.let { throw LastFmException(it, body.message ?: "no message") }
        body.session ?: throw LastFmException(0, "Last.fm returned neither a session nor an error")
    }

    /**
     * "Listening now", which is not a scrobble and is not stored. Sent when a song starts, and
     * allowed to fail: it is decoration, and the scrobble is the part that matters.
     */
    suspend fun updateNowPlaying(
        sessionKey: String,
        artist: String,
        track: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): Result<Unit> = post(
        "track.updateNowPlaying",
        sessionKey,
        buildMap {
            put("artist", artist)
            put("track", track)
            album?.let { put("album", it) }
            durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
        },
    )

    /**
     * A play, recorded permanently against the account.
     *
     * @param timestampSeconds when playback STARTED, in seconds since the epoch. Last.fm orders a
     *        listening history by this, so passing the finish time quietly shifts every entry by
     *        the length of the song.
     */
    suspend fun scrobble(
        sessionKey: String,
        artist: String,
        track: String,
        timestampSeconds: Long,
        album: String? = null,
        durationSeconds: Int? = null,
    ): Result<Unit> = post(
        "track.scrobble",
        sessionKey,
        buildMap {
            put("artist", artist)
            put("track", track)
            put("timestamp", timestampSeconds.toString())
            album?.let { put("album", it) }
            durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
        },
    )

    private suspend fun post(
        method: String,
        sessionKey: String,
        fields: Map<String, String>,
    ): Result<Unit> = runCatching {
        val params = fields + mapOf("method" to method, "api_key" to apiKey, "sk" to sessionKey)
        val response = client.submitForm(
            url = ROOT,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
                append("api_sig", sign(params))
                append("format", "json")
            },
        )
        val body = response.body<ApiResult>()
        if (body.error != null) {
            throw LastFmException(body.error, body.message ?: "no message")
        }
    }

    /**
     * Last.fm's signature: every parameter except format, sorted by name, concatenated as
     * name then value with nothing between them, the shared secret appended, then MD5.
     *
     * Sorting is by UTF-8 byte order and the concatenation has no separators, so this is easy to
     * get subtly wrong and be told only "Invalid method signature".
     */
    private fun sign(params: Map<String, String>): String {
        val joined = params.toSortedMap().entries.joinToString("") { (k, v) -> "$k$v" } + apiSecret
        return MessageDigest.getInstance("MD5")
            .digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ROOT = "https://ws.audioscrobbler.com/2.0/"

        /**
         * Last.fm's own rule for what counts: at least half the track, or four minutes, whichever
         * comes first, and never anything under thirty seconds. Kept here beside the client so the
         * caller does not have to invent its own threshold and drift from the service's.
         */
        fun qualifies(playedMs: Long, durationSeconds: Int): Boolean {
            if (durationSeconds < 30) return false
            val target = minOf(durationSeconds * 1000L / 2, 4 * 60 * 1000L)
            return playedMs >= target
        }

        fun defaultClient() = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
}

class LastFmException(val code: Int, override val message: String) : Exception(message)
