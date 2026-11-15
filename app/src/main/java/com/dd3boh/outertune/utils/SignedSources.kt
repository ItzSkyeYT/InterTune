/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.RevokedSigningKeysKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetching the signed documents ([SignedDocument]) from their hosts: every host asked at once, each given a
 * short while, and the newest document to believe wins, wherever it came from.
 *
 * Until 0.13 the news came from one gist, and the update check and the donors from GitHub alone, so one ban of
 * one GitHub account took every way of reaching the app's users at once. More gists on the same account would
 * not help: the other hosts are other companies (Netlify, Cloudflare, Codeberg).
 */
object SignedSources {
    private const val TAG = "SignedSources"

    /** One small text fetched, or why not, in a few words for the log (never the address). */
    sealed interface Fetched {
        data class Text(val body: String) : Fetched
        data class Failed(val why: String) : Fetched
    }

    /** The host whose document was taken, by its place in the list. */
    data class Found(val index: Int, val accepted: SignedDocument.Accepted)

    /**
     * What the hosts gave. [answered]: some host answered with a signed document, even one refused (an older
     * copy), so the hosts are there; not nobody at all, or a hotel's sign-in page. [notes]: a line for each
     * host, in the list's order, for the log.
     */
    class Walk(val found: Found?, val answered: Boolean, val notes: List<String>)

    /**
     * A short while per host. The hosts are asked at once, so the slowest bounds the whole: 15 seconds at
     * most, in the background. A host that is down is most often down at once (no such name, refused), and
     * one that hangs is cut off.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The file at [url] as text, at most [most] bytes: a larger one is refused before it is all read. */
    fun fetch(url: String, client: OkHttpClient = this.client, most: Long = 2L * SignedDocument.MOST_BYTES): Fetched {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return Fetched.Failed("HTTP ${response.code}")
                val body = response.body ?: return Fetched.Failed("no body")
                if (body.contentLength() > most) return Fetched.Failed("too large")
                val source = body.source()
                if (source.request(most + 1)) return Fetched.Failed("too large")
                return Fetched.Text(source.buffer.readUtf8())
            }
        } catch (e: Exception) {
            // A timeout, no network, no such host, an address that is not one: never the address itself.
            return Fetched.Failed(ErrorText.withoutAddresses("${e.javaClass.simpleName}: ${e.message}".take(160)))
        }
    }

    /**
     * Asks every one of [sources] at once and takes, among the documents [open] believes, the one with the
     * highest number. So a host that still serves an older copy (a gist nobody can change any more, a copy not
     * put live yet) never holds the app back while another host has something newer. The order of [sources]
     * only decides between copies with the same number. A host that fails, or serves a document [open] refuses
     * (not signed, signed by someone else, older than one taken, expired), counts for nothing.
     *
     * [lastResort] is asked, the same way, only when [sources] give nothing to believe: a host that is there
     * for the day the others are gone, not for every check (Codeberg is not a CDN). Its hosts are numbered on
     * from [sources]' in the log.
     */
    suspend fun best(
        sources: List<String>,
        fetch: (String) -> Fetched,
        lastResort: List<String> = emptyList(),
        open: (String) -> SignedDocument.Result,
    ): Walk {
        val first = together(sources, 0, fetch, open)
        if (first.found != null || lastResort.isEmpty()) return first
        val last = together(lastResort, sources.size, fetch, open)
        return Walk(last.found, first.answered || last.answered, first.notes + last.notes)
    }

    /** [best] for one set of hosts, asked at once, numbered in the log from [offset] + 1. */
    private suspend fun together(
        sources: List<String>,
        offset: Int,
        fetch: (String) -> Fetched,
        open: (String) -> SignedDocument.Result,
    ): Walk {
        val answers = coroutineScope {
            sources.map { url ->
                async(Dispatchers.IO) {
                    runCatching { fetch(url) }.getOrElse { Fetched.Failed(it.javaClass.simpleName) }
                }
            }.awaitAll()
        }
        val results = answers.map { got -> (got as? Fetched.Text)?.let { open(it.body) } }

        val best = run {
            var top: Found? = null
            for (i in sources.indices) {
                val result = results[i]
                if (result is SignedDocument.Accepted && (top == null || result.seq > top.accepted.seq)) top = Found(offset + i, result)
            }
            top
        }

        var answered = false
        val notes = sources.indices.map { i ->
            val where = "host ${offset + i + 1} (${host(sources[i])})"
            when (val result = results[i]) {
                null -> "$where: ${(answers[i] as Fetched.Failed).why}"
                is SignedDocument.Refused -> {
                    answered = answered || result.signed
                    "$where: refused, ${result.why}"
                }
                is SignedDocument.Accepted -> {
                    answered = true
                    val taken = best!!
                    when {
                        taken.index == offset + i -> "$where: taken, number ${result.seq}"
                        result.seq < taken.accepted.seq -> "$where: number ${result.seq}, older than host ${taken.index + 1}'s"
                        else -> "$where: number ${result.seq}, the same as host ${taken.index + 1}'s"
                    }
                }
            }
        }
        return Walk(best, answered, notes)
    }

    /** The host alone, for the log: the gist's own address is never written anywhere. */
    fun host(url: String): String = url.toHttpUrlOrNull()?.host ?: "not an address"

    /**
     * Keeps for good what a document taken changes: its number under [seqKey], the highest of each kind, and
     * any key it withdraws.
     */
    suspend fun remember(context: Context, accepted: SignedDocument.Accepted, seqKey: Preferences.Key<Long>) {
        context.dataStore.edit {
            it[seqKey] = maxOf(it[seqKey] ?: 0L, accepted.seq)
            if (accepted.revokes.isNotEmpty()) {
                it[RevokedSigningKeysKey] = it[RevokedSigningKeysKey].orEmpty() + accepted.revokes
                Log.w(TAG, "A document signed with the spare key withdrew ${accepted.revokes}")
            }
        }
    }

    /**
     * The payload of the one [kind] document at [url] (the donors, the newest version), if it is one to
     * believe, with its number kept under [seqKey]. Null for anything else, and the log says why.
     */
    suspend fun one(context: Context, url: String, kind: String, seqKey: Preferences.Key<Long>): String? {
        val store = context.dataStore
        val now = System.currentTimeMillis()
        val highest = store.get(seqKey, 0L)
        val revoked = store.get(RevokedSigningKeysKey, emptySet())
        val walk = best(listOf(url), { fetch(it) }) {
            SignedDocument.open(it, kind, SigningKeys.BUILT_IN, highest, revoked, now)
        }
        walk.notes.forEach { Log.i(TAG, "$kind: $it") }
        val accepted = walk.found?.accepted ?: return null
        remember(context, accepted, seqKey)
        return accepted.payload
    }
}
