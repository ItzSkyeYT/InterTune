package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.lastfm.LastFm
import com.dd3boh.lastfm.LastFmException
import com.dd3boh.lastfm.SimilarTrack
import com.dd3boh.outertune.constants.LastFmRefusedAtKey
import com.dd3boh.outertune.constants.LastFmScrobbleKey
import com.dd3boh.outertune.constants.LastFmSessionKey
import com.dd3boh.outertune.constants.LastFmUsernameKey
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.listenbrainz.ListenBrainzSender
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ListenReporting
import com.dd3boh.outertune.playback.ListenRules
import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends plays to Last.fm, if the user has connected an account, and hands each to ListenBrainz
 * beside it ([ListenBrainzSender], Unreleased.LISTENBRAINZ), on the same rules.
 *
 * Deliberately quiet. A scrobble that fails is not worth a message: the user did not ask for one at
 * that moment, the cause is almost always the network, and the play is already counted in
 * InterTune's own history either way. Failures are logged and dropped.
 *
 * There is no offline queue yet. That is the obvious next step and is noted in the TODO rather
 * than half built here, because a queue that survives restarts needs a table, and adding one means
 * a database migration.
 *
 * Every call checks the key first, and from 1 December 2026 the key reads as empty (LastFmEnd),
 * so from then nothing here reaches Last.fm.
 *
 * @param settings the app's settings, read from memory.
 * @param connect Last.fm's client for a key and its secret. A test hands in one over a fake network.
 */
@Singleton
class Scrobbler internal constructor(
    private val settings: SettingsSnapshot,
    private val connect: (apiKey: String, apiSecret: String) -> LastFm,
class Scrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    /** ListenBrainz's own sending, which the settings card connects and disconnects. */
    val listenBrainz: ListenBrainzSender,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.dataStore, { key, secret -> LastFm(key, secret) })

    // Empty in a build without them, in any copy not signed with the release key, and from
    // 1 December 2026 on. See BuiltInKeys and LastFmEnd.
    private val apiKey get() = BuiltInKeys.lastFmApiKey
    private val apiSecret get() = BuiltInKeys.lastFmApiSecret

    private val configured get() = apiKey.isNotEmpty() && apiSecret.isNotEmpty()

    val isAvailable get() = configured

    /** Similar tracks are a public read: the key is enough, with or without an account. */
    val canFindSimilar get() = apiKey.isNotEmpty()

    private val api by lazy { connect(apiKey, apiSecret) }

    private fun sessionOrNull(): String? {
        if (!configured) return null
        if (!settings.get(LastFmScrobbleKey, true)) return null
        return settings.get(LastFmSessionKey, "").takeIf { it.isNotBlank() }
    }

    /**
     * Last.fm refusing the key itself closes it here and now, before 1 December too: written
     * down once (LastFmEnd), so the key reads as empty from the next call on, after a restart as
     * well, and nothing more is sent. Every answer from Last.fm passes through here.
     */
    private suspend fun <T> Result<T>.noted(): Result<T> {
        val failure = exceptionOrNull() ?: return this
        if (LastFmEnd.refusesKey(failure) && settings[LastFmRefusedAtKey] == null) {
            Log.w(TAG, "Last.fm refused the key (code ${(failure as LastFmException).code}), closing it")
            settings.edit { it[LastFmRefusedAtKey] = LastFmEnd.clock() }
        }
        return this
    }

    /** Step one of connecting: the token, and where to send the user to approve it. */
    suspend fun beginLogin(): Result<Pair<String, String>> {
        if (!configured) return Result.failure(IllegalStateException("No Last.fm API key in this build"))
        return api.requestToken().noted().map { token -> token to api.authorizeUrl(token) }
    }

    /**
     * Step two, after the user has approved in the browser. Checks the key like every other call:
     * without that the login screen, still open when Last.fm closed, went on asking it.
     */
    suspend fun completeLogin(token: String): Result<String> {
        if (!configured) return Result.failure(IllegalStateException("No Last.fm API key in this build"))
        return api.session(token).noted().onSuccess { session ->
            settings.edit {
                it[LastFmSessionKey] = session.key
                it[LastFmUsernameKey] = session.name
            }
        }.map { it.name }
    }

    suspend fun logout() = forget(settings)

    suspend fun nowPlaying(metadata: MediaMetadata) {
        if (!ListenReporting.sendsNowPlaying(settings.get(PauseListenHistoryKey, false))) return
        val session = sessionOrNull() ?: return
        val artist = primaryArtist(metadata.artists.map { it.name }) ?: return
    suspend fun nowPlaying(metadata: MediaMetadata): Unit = coroutineScope {
        if (!ListenReporting.sendsNowPlaying(context.dataStore.get(PauseListenHistoryKey, false))) return@coroutineScope
        // ListenBrainz beside Last.fm, neither waiting for the other's answer.
        launch { listenBrainz.nowPlaying(metadata) }
        val session = sessionOrNull() ?: return@coroutineScope
        val artist = primaryArtist(metadata.artists.map { it.name }) ?: return@coroutineScope
        api.updateNowPlaying(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            album = metadata.album?.title,
            durationSeconds = metadata.duration.takeIf { it > 0 },
        ).noted().onFailure { handleFailure("now playing", it) }
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
    ): Unit = coroutineScope {
        val duration = durationSeconds
        // One rule for Last.fm and ListenBrainz, so a play does not count for one and not the other.
        if (!ListenRules.qualifies(playedMs, duration)) return@coroutineScope
        launch { listenBrainz.scrobble(metadata, startedAtSeconds, duration) }
        val session = sessionOrNull() ?: return@coroutineScope
        val artist = primaryArtist(metadata.artists.map { it.name }) ?: return@coroutineScope
        api.scrobble(
            sessionKey = session,
            artist = artist,
            track = metadata.title,
            timestampSeconds = startedAtSeconds,
            album = metadata.album?.title,
            durationSeconds = duration.takeIf { it > 0 },
        ).noted().onFailure { handleFailure("scrobble", it) }
    }

    /** Last.fm's tracks most like this one, most similar first. */
    suspend fun similar(artist: String, track: String): Result<List<SimilarTrack>> {
        if (!canFindSimilar) return Result.failure(IllegalStateException("No Last.fm API key in this build"))
        return api.similar(artist, track).noted()
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

        /**
         * Once Last.fm has closed (LastFmEnd), forgets the account: a sign-in nobody can use any
         * more stays out of the settings and out of a backup's sign-ins. Run at each start, it has
         * something to do once. The scrobble switch and the choice of similar songs are kept.
         */
        suspend fun forgetIfClosed(settings: SettingsSnapshot) {
            if (!LastFmEnd.closed()) return
            if (settings[LastFmSessionKey] == null && settings[LastFmUsernameKey] == null) return
            Log.i(TAG, "Last.fm has closed, forgetting the account")
            forget(settings)
        }

        private suspend fun forget(settings: SettingsSnapshot) {
            settings.edit {
                it.remove(LastFmSessionKey)
                it.remove(LastFmUsernameKey)
            }
        }

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
