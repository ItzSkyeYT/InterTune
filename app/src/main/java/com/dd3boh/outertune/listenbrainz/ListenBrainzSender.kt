/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.listenbrainz.ListenBrainz
import com.dd3boh.listenbrainz.ListenBrainzException
import com.dd3boh.listenbrainz.models.TokenCheck
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.constants.ListenBrainzAccountRefusedKey
import com.dd3boh.outertune.constants.ListenBrainzRefusedAtKey
import com.dd3boh.outertune.constants.ListenBrainzScrobbleKey
import com.dd3boh.outertune.constants.ListenBrainzTokenKey
import com.dd3boh.outertune.constants.ListenBrainzUserKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ListenRules
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays to ListenBrainz (Unreleased.LISTENBRAINZ), beside Last.fm: Scrobbler hands each play to
 * both, on the same rules (ListenRules), and the settings card connects and disconnects here.
 *
 * Quiet, as Last.fm's sending is: a play that cannot go now is no message, it waits in the
 * outbox. Logged in a line each, never with the token: no ListenBrainzException holds it.
 */
@Singleton
class ListenBrainzSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outbox: ListenOutbox,
) {
    /** One client for the app, so every request waits its turn at the same gate. */
    val client: ListenBrainz by lazy { ListenBrainz(ListenBrainz.App(CLIENT_NAME, BuildConfig.VERSION_NAME, Links.REPO)) }

    private val token get() = context.dataStore.get(ListenBrainzTokenKey, "")
    private val user get() = context.dataStore.get(ListenBrainzUserKey, "")

    /** ListenBrainz said no to the token: plays wait for a new one. */
    private val tokenRefused get() = context.dataStore.get(ListenBrainzRefusedAtKey, 0L) > 0L

    /** ListenBrainz knows the token but takes no listens from the account for now (ListenBrainzStanding). */
    private val accountRefused get() = context.dataStore.get(ListenBrainzAccountRefusedKey, "").isNotEmpty()

    /** Connected, with Scrobble what I play on. */
    private fun sending(): Boolean =
        Unreleased.LISTENBRAINZ && token.isNotBlank() && context.dataStore.get(ListenBrainzScrobbleKey, true)

    /**
     * "Playing now", at a song's first sound. Never kept: by the time it could go, another song
     * plays. Not sent while the account is refused either, where it would only be refused again.
     */
    suspend fun nowPlaying(metadata: MediaMetadata) {
        if (!sending() || tokenRefused || accountRefused) return
        val listen = ListenRules.listenOf(client, metadata, startedAtSeconds = null, durationSeconds = metadata.duration) ?: return
        val result = client.playingNow(token, listen)
        result.onFailure { e -> Log.i(TAG, "ListenBrainz: playing now not sent, ${said(e)}") }
        note(result)
    }

    /**
     * A play that counts (ListenRules.qualifies, checked by Scrobbler), kept in the outbox before it
     * is sent: nothing is lost if the app dies before ListenBrainz answers. Off the outbox once
     * ListenBrainz takes it or refuses it for good; otherwise ListenBrainzWorker sends it later.
     */
    suspend fun scrobble(metadata: MediaMetadata, startedAtSeconds: Long, durationSeconds: Int) {
        if (!sending()) return
        val listen = ListenRules.listenOf(client, metadata, startedAtSeconds, durationSeconds) ?: return
        val kept = WaitingListen(user = user, json = ListenBrainz.encode(listen), keptAt = System.currentTimeMillis())
        outbox.keep(kept)
        // It waits for a token that works, and goes with what else waits once there is one.
        if (tokenRefused) return
        // Sent while the account is refused all the same: a play taken is how the app learns that
        // ListenBrainz takes them again, the email verified.
        val result = client.single(token, listen)
        result
            .onSuccess {
                outbox.remove(listOf(kept))
                Log.i(TAG, "ListenBrainz: listen sent")
            }
            .onFailure { e ->
                when (e) {
                    is ListenBrainzException.Refused -> {
                        outbox.remove(listOf(kept))
                        Log.w(TAG, "ListenBrainz: refused (${e.status}): ${e.message}")
                    }
                    // It waits for a new token, or for the account to take listens again: note() says which.
                    is ListenBrainzException.TokenRefused, is ListenBrainzException.AccountRefused -> Unit
                    else -> {
                        Log.i(TAG, "ListenBrainz: ${outbox.of(kept.user).size} waiting, ${said(e)}")
                        ListenBrainzWorker.schedule(context)
                    }
                }
            }
        note(result)
    }

    /** What waits in the outbox, sent: ListenBrainzWorker's run. */
    suspend fun sendWaiting(): OutboxRun.Next {
        if (!sending() || tokenRefused) return OutboxRun.Next.DONE
        val key = token
        val waiting = outbox.of(user)
        if (waiting.isEmpty()) return OutboxRun.Next.DONE
        val outcome = OutboxRun.run(waiting) { client.import(key, it) }
        outbox.remove(outcome.sent + outcome.dropped)
        outcome.refusals.forEach { Log.w(TAG, "ListenBrainz: refused: $it") }
        val left = waiting.size - outcome.sent.size - outcome.dropped.size
        Log.i(TAG, "ListenBrainz: ${outcome.sent.size} sent, ${outcome.dropped.size} dropped, $left waiting" +
                (outcome.stoppedBy?.let { ", ${said(it)}" } ?: ""))
        if (outcome.sent.isNotEmpty()) note(Result.success(Unit))
        outcome.stoppedBy?.let { note(Result.failure<Unit>(it)) }
        return outcome.next
    }

    /**
     * Asks ListenBrainz whose [pasted] is, and keeps it when ListenBrainz knows it, with the
     * account's name. Plays kept under another account are dropped, a refusal of an earlier token
     * or of the account is forgotten, and what waits goes: the account's refusal, if it stands,
     * comes back with ListenBrainz's next answer. A token ListenBrainz does not know is success with
     * valid false; a failure is no answer.
     */
    suspend fun connect(pasted: String): Result<TokenCheck> {
        val key = pasted.trim()
        return client.validateToken(key).onSuccess { check ->
            if (!check.valid) return@onSuccess
            val name = check.userName.orEmpty()
            context.dataStore.edit {
                it[ListenBrainzTokenKey] = key
                it[ListenBrainzUserKey] = name
                it.remove(ListenBrainzRefusedAtKey)
                it.remove(ListenBrainzAccountRefusedKey)
            }
            outbox.keepOnly(name)
            if (ListenOutbox.anyWaiting(context)) ListenBrainzWorker.schedule(context)
            Log.i(TAG, "ListenBrainz: connected")
        }
    }

    /** Forgets the token, the account's name and every play waiting. What ListenBrainz has, it keeps. */
    suspend fun disconnect() {
        context.dataStore.edit {
            it.remove(ListenBrainzTokenKey)
            it.remove(ListenBrainzUserKey)
            it.remove(ListenBrainzRefusedAtKey)
            it.remove(ListenBrainzAccountRefusedKey)
        }
        outbox.clear()
        Log.i(TAG, "ListenBrainz: disconnected")
    }

    /**
     * What ListenBrainz's answer to listens says about the token and the account, kept where the
     * card reads it (ListenBrainzStanding). Only a token ListenBrainz does not know is refused().
     */
    private suspend fun note(result: Result<*>) {
        when (val standing = ListenBrainzStanding.after(result)) {
            ListenBrainzStanding.TAKEN -> if (accountRefused) {
                context.dataStore.edit { it.remove(ListenBrainzAccountRefusedKey) }
                Log.i(TAG, "ListenBrainz: the account takes listens again")
                // What waited for it goes now.
                ListenBrainzWorker.schedule(context)
            }
            ListenBrainzStanding.TOKEN_REFUSED -> refused(result.exceptionOrNull() as ListenBrainzException.TokenRefused)
            ListenBrainzStanding.NEEDS_EMAIL, ListenBrainzStanding.ACCOUNT_REFUSED -> {
                Log.w(TAG, "ListenBrainz: the token works, the account takes no listens, ${said(result.exceptionOrNull()!!)}")
                if (context.dataStore.get(ListenBrainzAccountRefusedKey, "") != standing.name) {
                    context.dataStore.edit { it[ListenBrainzAccountRefusedKey] = standing.name }
                }
            }
            null -> Unit
        }
    }

    private suspend fun refused(e: ListenBrainzException.TokenRefused) {
        Log.w(TAG, "ListenBrainz: the token was refused (${e.status}): ${e.message}")
        context.dataStore.edit { it[ListenBrainzRefusedAtKey] = System.currentTimeMillis() }
    }

    companion object {
        private const val TAG = "ListenBrainz"

        /** The name ListenBrainz knows this app by: its User-Agent, each listen's player and client. A fork puts its own here. */
        const val CLIENT_NAME = "InterTune"

        /** A failure in a few words for the log. A ListenBrainzException's text never holds the token. */
        fun said(e: Throwable): String = when (e) {
            is ListenBrainzException.Unreachable -> "no connection (${e.message})"
            is ListenBrainzException.RateLimited -> "asked to wait ${(e.retryAfterMs + 999) / 1000} s"
            is ListenBrainzException.Unavailable -> "ListenBrainz unavailable (${e.status}): ${e.message}"
            is ListenBrainzException.Refused -> "refused (${e.status}): ${e.message}"
            is ListenBrainzException.TokenRefused -> "token refused (${e.status}): ${e.message}"
            is ListenBrainzException.AccountRefused -> "account refused (${e.status}): ${e.message}"
            else -> e.javaClass.simpleName
        }
    }
}
