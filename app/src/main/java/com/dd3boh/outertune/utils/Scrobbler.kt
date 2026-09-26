package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.lastfm.LastFm
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.lastfm.SimilarTrack
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ListenReporting
import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends plays to Last.fm, if the user has connected an account.
 *
 * Deliberately quiet. A scrobble that fails is not worth a message: the user did not ask for one at
 * that moment, the cause is almost always the network, and the play is already counted in
 * InterTune's own history either way. Failures are logged and dropped.
 *
 * There is no offline queue yet. That is the obvious next step and is noted in the TODO rather
 * than half built here, because a queue that survives restarts needs a table, and adding one means
 * a database migration.
 */
@Singleton
class Scrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Empty in a build without them and in any copy not signed with the release key. See BuiltInKeys.
    private val apiKey get() = BuiltInKeys.lastFmApiKey
    private val apiSecret get() = BuiltInKeys.lastFmApiSecret

    private val configured get() = apiKey.isNotEmpty() && apiSecret.isNotEmpty()

    val isAvailable get() = configured

    /** Similar tracks are a public read: the key is enough, with or without an account. */
    val canFindSimilar get() = apiKey.isNotEmpty()

    private val api by lazy { LastFm(apiKey, apiSecret) }

    private fun sessionOrNull(): String? {
        if (!configured) return null
        if (!context.dataStore.get(LastFmScrobbleKey, true)) return null
        return context.dataStore.get(LastFmSessionKey, "").takeIf { it.isNotBlank() }
    }

    /** Step one of connecting: the token, and where to send the user to approve it. */
    suspend fun beginLogin(): Result<Pair<String, String>> {
        if (!configured) return Result.failure(IllegalStateException("No Last.fm API key in this build"))
        return api.requestToken().map { token -> token to api.authorizeUrl(token) }
    }

    /** Step two, after the user has approved in the browser. */
    suspend fun completeLogin(token: String): Result<String> =
        api.session(token).onSuccess { session ->
            context.dataStore.edit {
                it[LastFmSessionKey] = session.key
                it[LastFmUsernameKey] = session.name
            }
        }.map { it.name }

    suspend fun logout() {
        context.dataStore.edit {
            it.remove(LastFmSessionKey)
            it.remove(LastFmUsernameKey)
        }
    }

    suspend fun nowPlaying(metadata: MediaMetadata) {
        if (!ListenReporting.sendsNowPlaying(context.dataStore.get(PauseListenHistoryKey, false))) return
        val session = sessionOrNull() ?: return
        val artist = primaryArtist(metadata.artists.map { it.name }) ?: return
        api.updateNowPlaying(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            album = metadata.album?.title,
            durationSeconds = metadata.duration.takeIf { it > 0 },
        ).onFailure { handleFailure("now playing", it) }
    }

    /**
     * @param playedMs how much of the song actually played, which is not the same as its position:
     *        a song can be seeked around in, and Last.fm asks about time listened.
     * @param startedAtSeconds when playback began. Last.fm orders history by this.
     * @param durationSeconds the song's length. The service passes the one it recovered: a song
     *        started from search results arrives with -1, which Last.fm's rule rejects, so none of
     *        them ever scrobbled.
     */
    suspend fun scrobble(
        metadata: MediaMetadata,
        playedMs: Long,
        startedAtSeconds: Long,
        durationSeconds: Int = metadata.duration,
    ) {
        val session = sessionOrNull() ?: return
        val duration = durationSeconds
        if (!LastFm.qualifies(playedMs, duration)) return
        val artist = primaryArtist(metadata.artists.map { it.name }) ?: return
        api.scrobble(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            timestampSeconds = startedAtSeconds,
            album = metadata.album?.title,
            durationSeconds = duration.takeIf { it > 0 },
        ).onFailure { handleFailure("scrobble", it) }
    }

    /** Last.fm's tracks most like this one, most similar first. */
    suspend fun similar(artist: String, track: String): Result<List<SimilarTrack>> {
        if (!canFindSimilar) return Result.failure(IllegalStateException("No Last.fm API key in this build"))
        return api.similar(artist, track)
    }

    private suspend fun handleFailure(what: String, t: Throwable) {
        logFailure(what, t)
        if (isInvalidSession(t)) {
            // Revoked on last.fm, or the password changed. Every later call fails the same way,
            // and Settings went on saying connected, so nobody knew to connect again.
            Log.w(TAG, "Last.fm says the session is no longer valid, disconnecting")
            logout()
        }
    }

    private fun logFailure(what: String, t: Throwable) {
        val detail = (t as? LastFmException)?.let { "code ${it.code}: ${it.message}" } ?: t.message
        Log.w(TAG, "Last.fm $what failed, dropping it: $detail")
    }

    companion object {
        private const val TAG = "Scrobbler"

        /** Last.fm's "Invalid session key - Please re-authenticate". */
        const val ERROR_INVALID_SESSION = 9

        fun isInvalidSession(t: Throwable): Boolean = (t as? LastFmException)?.code == ERROR_INVALID_SESSION

        /**
         * The artist Last.fm is sent: the first one, as the similar-songs lookup already does.
         * All of them joined ("A, B") is an artist that does not exist, so the scrobble landed on
         * a page of its own instead of the artist's.
         */
        fun primaryArtist(names: List<String>): String? =
            names.firstNotNullOfOrNull { name -> name.trim().takeIf { it.isNotEmpty() } }
    }
}
