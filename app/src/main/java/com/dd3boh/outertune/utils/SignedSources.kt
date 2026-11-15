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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetching the signed documents ([SignedDocument]) from their hosts: one small file at a time, each host given
 * a short while, in order, and the first that answers with a document to believe wins.
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

    /** The first host that gave a document to believe, by its place in the list. */
    data class Found(val index: Int, val accepted: SignedDocument.Accepted)

    /**
     * Where a walk through the hosts ended. [answered]: some host answered with a signed document, even one
     * refused (an older copy), so the hosts are there; not nobody at all, or a hotel's sign-in page. [notes]:
     * a line for each host asked, for the log.
     */
    class Walk(val found: Found?, val answered: Boolean, val notes: List<String>)

    /**
     * A short while per host, so four that do not answer cost a minute at most, in the background: a host
     * that is down is most often down at once (no such name, refused), and one that hangs is cut off.
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
     * Asks [sources] in order and stops at the first whose file [open] takes. A host that fails, or answers with
     * a document [open] refuses (not signed, signed by someone else, older than one taken, expired), is passed
     * over for the next.
     */
    fun first(sources: List<String>, fetch: (String) -> Fetched, open: (String) -> SignedDocument.Result): Walk {
        val notes = mutableListOf<String>()
        var answered = false
        sources.forEachIndexed { i, url ->
            val where = "host ${i + 1} (${host(url)})"
            when (val got = fetch(url)) {
                is Fetched.Failed -> notes += "$where: ${got.why}"
                is Fetched.Text -> when (val result = open(got.body)) {
                    is SignedDocument.Accepted -> {
                        notes += "$where: taken, number ${result.seq}"
                        return Walk(Found(i, result), true, notes)
                    }
                    is SignedDocument.Refused -> {
                        answered = answered || result.signed
                        notes += "$where: refused, ${result.why}"
                    }
                }
            }
        }
        return Walk(null, answered, notes)
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
        val walk = first(listOf(url), { fetch(it) }) {
            SignedDocument.open(it, kind, SigningKeys.BUILT_IN, highest, revoked, now)
        }
        walk.notes.forEach { Log.i(TAG, "$kind: $it") }
        val accepted = walk.found?.accepted ?: return null
        remember(context, accepted, seqKey)
        return accepted.payload
    }
}
