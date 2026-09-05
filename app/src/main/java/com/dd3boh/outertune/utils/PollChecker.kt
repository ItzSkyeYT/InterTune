/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.AnsweredPollIdsKey
import com.dd3boh.outertune.constants.CachedPollsJsonKey
import com.dd3boh.outertune.constants.DismissedPollIdsKey
import com.dd3boh.outertune.constants.LastPollFetchKey
import com.dd3boh.outertune.constants.Polls
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.extensions.isInternetConnected
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the occasional question from the maintainer, and sends back an answer.
 *
 * Deliberately shaped like [UpdateChecker], because the problem is the same one: read a small
 * json document from a third party, remember what it said across restarts, and never pester
 * somebody about a thing they have already dealt with.
 *
 * WHAT LEAVES THE DEVICE, in full, because this is the part that has to be true when the modal
 * says the answer is anonymous:
 *  - a GET for the poll list, which reveals only that some InterTune install looked
 *  - on answering, one POST carrying the poll id, the chosen option ids, and the app version
 * No account, no device id, no install id, no library, nothing derived from the hardware. Umami
 * works out its own visitor figure server side from a rotating hash it computes itself, so the app
 * has no need to identify anyone and does not.
 *
 * Opt in, and silent about it. Until [PollsEnabledKey] is true nothing is fetched at all, so there
 * is not even a request to explain.
 */
@Singleton
class PollChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Option(
        val id: String,
        val label: String,
        /** Optional picture for this choice. */
        val imageUrl: String?,
    )

    data class Poll(
        val id: String,
        /** One line, for the banner at the top of Home. */
        val banner: String,
        /** The question itself, shown in the modal. */
        val question: String,
        /** Optional longer explanation under the question. */
        val body: String?,
        /** Optional picture above the question. */
        val imageUrl: String?,
        val options: List<Option>,
        /** True when more than one option may be chosen. */
        val multiple: Boolean,
    )

    private val _current = MutableStateFlow<Poll?>(null)

    /** The question worth asking right now, or null when there is nothing to ask. */
    val current: StateFlow<Poll?> = _current.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Looks for a question that has not been dealt with.
     *
     * [force] skips the rate limit, for the check button on the settings screen.
     */
    suspend fun check(force: Boolean = false): Poll? = withContext(Dispatchers.IO) {
        if (!Polls.isConfigured) return@withContext null

        val store = context.dataStore
        if (!store.get(PollsEnabledKey, false)) return@withContext null
        if (!context.isInternetConnected()) return@withContext null

        val last = store.get(LastPollFetchKey, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - last < MIN_FETCH_INTERVAL_MS) {
            // Re-derive from the cached document rather than reporting the in-memory value, which
            // is null on every process start. Same fault the update checker had.
            return@withContext restoreFromCache()
        }

        val body = runCatching {
            client.newCall(Request.Builder().url(Polls.POLLS_URL).build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.string()
                }
        }.getOrNull() ?: run {
            Log.i(TAG, "Poll fetch failed, leaving the last fetch time alone so it retries")
            return@withContext restoreFromCache()
        }

        // Only stamp the clock once something actually answered, so one flaky moment does not
        // suppress fetching for the whole interval.
        context.dataStore.edit {
            it[LastPollFetchKey] = now
            it[CachedPollsJsonKey] = body
        }

        pick(body)
    }

    /** Re-reads the cached document and picks again, since what counts as unanswered has moved. */
    private suspend fun restoreFromCache(): Poll? {
        val cached = context.dataStore.get(CachedPollsJsonKey, "")
        if (cached.isEmpty()) return null
        return pick(cached)
    }

    /**
     * Chooses the first question this user has not dealt with.
     *
     * Order is the maintainer's: whatever is first in the document and still applicable wins, so
     * questions can be queued up and worked through one at a time rather than all at once.
     */
    private suspend fun pick(json: String): Poll? {
        val store = context.dataStore
        val answered = store.get(AnsweredPollIdsKey, emptySet())
        val dismissed = store.get(DismissedPollIdsKey, emptySet())

        val chosen = runCatching { parse(json) }.getOrNull().orEmpty().firstOrNull {
            it.id !in answered && it.id !in dismissed
        }
        _current.value = chosen
        if (chosen != null) Log.i(TAG, "Poll available: ${chosen.id}")
        return chosen
    }

    /**
     * Reads the poll list.
     *
     * org.json rather than kotlinx.serialization because this module does not apply the
     * serialization plugin, and one document is not worth a build change for.
     *
     * A malformed entry is skipped rather than failing the document, so one bad poll cannot stop
     * every later one from being asked. Version bounds are applied here: a question about a feature
     * makes no sense to somebody on a build that does not have it.
     */
    private fun parse(json: String): List<Poll> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("polls") ?: JSONArray()
        val now = System.currentTimeMillis()

        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)

                val min = o.optInt("minVersionCode", 0)
                val max = o.optInt("maxVersionCode", Int.MAX_VALUE)
                if (BuildConfig.VERSION_CODE < min || BuildConfig.VERSION_CODE > max) return@runCatching null

                // Epoch millis. A poll that outlives its usefulness stops asking on its own, so
                // somebody who has not opened the app in months is not met with a stale question.
                val expires = o.optLong("expiresAt", 0L)
                if (expires in 1 until now) return@runCatching null

                val optionsArr = o.optJSONArray("options") ?: return@runCatching null
                val options = (0 until optionsArr.length()).mapNotNull { j ->
                    val oo = optionsArr.optJSONObject(j) ?: return@mapNotNull null
                    val id = oo.optString("id").ifEmpty { return@mapNotNull null }
                    val label = oo.optString("label").ifEmpty { return@mapNotNull null }
                    Option(id, label, oo.optString("image").ifEmpty { null })
                }
                if (options.isEmpty()) return@runCatching null

                Poll(
                    id = o.optString("id").ifEmpty { return@runCatching null },
                    banner = o.optString("banner").ifEmpty { return@runCatching null },
                    question = o.optString("question").ifEmpty { return@runCatching null },
                    body = o.optString("body").ifEmpty { null },
                    imageUrl = o.optString("image").ifEmpty { null },
                    options = options,
                    multiple = o.optBoolean("multiple", false),
                )
            }.getOrNull()
        }
    }

    /**
     * Sends one answer, then never asks that question again.
     *
     * The local record is written whatever the network does. Somebody who answered should not be
     * asked a second time because a request failed, and a lost answer is a far smaller problem than
     * a modal that keeps reappearing.
     */
    suspend fun answer(poll: Poll, optionIds: List<String>) = withContext(Dispatchers.IO) {
        markAnswered(poll.id)

        if (!Polls.isConfigured) return@withContext
        runCatching {
            val payload = JSONObject()
                .put("website", Polls.UMAMI_WEBSITE_ID)
                .put("hostname", Polls.UMAMI_HOSTNAME)
                .put("name", Polls.UMAMI_EVENT)
                .put(
                    "data", JSONObject()
                        .put("poll", poll.id)
                        .put("answer", optionIds.sorted().joinToString(","))
                        .put("version", BuildConfig.VERSION_NAME)
                )
            val envelope = JSONObject().put("payload", payload).put("type", "event")

            client.newCall(
                Request.Builder()
                    .url("${Polls.UMAMI_URL}/api/send")
                    // Umami drops requests with no proper User-Agent, and it also derives its own
                    // visitor figure from a hash of this and the address. A fixed string is used on
                    // purpose: nothing here varies by device, so nothing here identifies a device.
                    .header("User-Agent", USER_AGENT)
                    .post(envelope.toString().toRequestBody(JSON))
                    .build()
            ).execute().use { it.isSuccessful }
        }.onFailure {
            Log.i(TAG, "Poll answer could not be sent, recorded locally anyway")
        }
        Unit
    }

    /** The banner's close button. Not answered, but not to be raised again either. */
    suspend fun dismiss(pollId: String) {
        context.dataStore.edit {
            it[DismissedPollIdsKey] = it[DismissedPollIdsKey].orEmpty() + pollId
        }
        _current.value = null
    }

    private suspend fun markAnswered(pollId: String) {
        context.dataStore.edit {
            it[AnsweredPollIdsKey] = it[AnsweredPollIdsKey].orEmpty() + pollId
        }
        _current.value = null
    }

    /** Forgets every local record, so the settings screen can offer a way to be asked again. */
    suspend fun forgetAll() {
        context.dataStore.edit {
            it.remove(AnsweredPollIdsKey)
            it.remove(DismissedPollIdsKey)
            it.remove(CachedPollsJsonKey)
            it.remove(LastPollFetchKey)
        }
        _current.value = null
    }

    private companion object {
        private const val TAG = "PollChecker"

        /**
         * Polls change far less often than releases, so this floor is much higher than the update
         * checker's. It is a gist behind a CDN, but there is no reason to ask more than this.
         */
        private const val MIN_FETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Fixed on purpose. See the note where it is used. */
        private const val USER_AGENT = "InterTune"
    }
}
