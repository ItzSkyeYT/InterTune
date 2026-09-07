package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.lastfm.LastFm
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.models.MediaMetadata
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
    private val configured =
        BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_API_SECRET.isNotBlank()

    val isAvailable get() = configured

    private val api by lazy {
        LastFm(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_API_SECRET)
    }

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
        val session = sessionOrNull() ?: return
        val artist = metadata.artists.joinToString { it.name }.ifBlank { return }
        api.updateNowPlaying(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            album = metadata.album?.title,
            durationSeconds = metadata.duration.takeIf { it > 0 },
        ).onFailure { logFailure("now playing", it) }
    }

    /**
     * @param playedMs how much of the song actually played, which is not the same as its position:
     *        a song can be seeked around in, and Last.fm asks about time listened.
     * @param startedAtSeconds when playback began. Last.fm orders history by this.
     */
    suspend fun scrobble(metadata: MediaMetadata, playedMs: Long, startedAtSeconds: Long) {
        val session = sessionOrNull() ?: return
        val duration = metadata.duration
        if (!LastFm.qualifies(playedMs, duration)) return
        val artist = metadata.artists.joinToString { it.name }.ifBlank { return }
        api.scrobble(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            timestampSeconds = startedAtSeconds,
            album = metadata.album?.title,
            durationSeconds = duration.takeIf { it > 0 },
        ).onFailure { logFailure("scrobble", it) }
    }

    private fun logFailure(what: String, t: Throwable) {
        val detail = (t as? LastFmException)?.let { "code ${it.code}: ${it.message}" } ?: t.message
        Log.w(TAG, "Last.fm $what failed, dropping it: $detail")
    }

    companion object {
        private const val TAG = "Scrobbler"
    }
}
