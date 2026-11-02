/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A listen made with no connection, kept to be named once there is one.
 *
 * @param heardAt when the first sample of the window was heard: the time the song is given in the
 * list of everything heard.
 * @param prints the window as heard, then as put back from the speeds Listen once would have tried
 * next ([SpeedRetry]), in the order they are asked about.
 * @param tries the times Shazam was asked and gave an answer that could not be read.
 */
data class KeptListen(val heardAt: Long, val prints: List<Fingerprint>, val tries: Int = 0)

/**
 * What's playing with no connection (issue #48): what it heard is kept, and the song is named once
 * the phone is back online, as Shazam's own app does.
 *
 * Shazam is only ever sent a fingerprint the phone makes of what it heard, so a listen with no
 * connection already holds everything but the answer. The fingerprint is what is kept, a few
 * kilobytes, never the sound. Listen once also keeps the window put back from the two speeds it
 * would have tried next, since the sound will not be there to try them later. RecogniseLaterWorker
 * asks once there is a connection, and a song it names goes into the list of everything heard at
 * the time it was heard.
 *
 * A listen waits at most [KEEP_MS], and no more than [MOST] wait at once, the oldest going first:
 * Keep listening with no connection keeps one about every two minutes, the pace [ShazamBackoff]
 * sets for failed requests. Clearing the list of everything heard clears this too.
 *
 * In a file of its own, in the app's files that are not backed up: the settings' file is read and
 * written whole at every change of any setting, and this one holds kilobytes a listen. Written
 * whole beside the old one and renamed into place, as ImportStore does, so a phone that dies in the
 * middle keeps the list it had. Written by hand with kotlinx.serialization's tree, as AdaptiveMemory
 * is: this module does not apply the serialization plugin, and its tests stub org.json.
 */
@Singleton
class RecogniseLater internal constructor(private val file: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, FILE))

    private val lock = Mutex()

    /** The list, once read from the file, and null until then. */
    private val loaded = MutableStateFlow<List<KeptListen>?>(null)

    /** Every listen waiting to be named, oldest first. */
    val waiting: Flow<List<KeptListen>> = loaded
        .onStart { withContext(Dispatchers.IO) { lock.withLock { load() } } }
        .filterNotNull()

    /** Keeps [listen]. One kept already from the same moment is replaced. */
    suspend fun keep(listen: KeptListen, now: Long = System.currentTimeMillis()) {
        change { kept(it, listen, now) }
    }

    /** What waits to be asked about at [now], oldest first, once what has waited too long is gone. */
    suspend fun due(now: Long = System.currentTimeMillis()): List<KeptListen> = change { fresh(it, now) }

    /** What [verdict] means for the listen heard at [heardAt]: see [Settled]. */
    suspend fun settle(heardAt: Long, verdict: Settled) {
        change { settled(it, heardAt, verdict) }
    }

    /**
     * Forgets every listen waiting, as clearing the list of everything heard does: the file goes
     * too, also one that could not be read.
     */
    suspend fun clear() {
        change(always = true) { emptyList() }
    }

    private suspend fun change(always: Boolean = false, how: (List<KeptListen>) -> List<KeptListen>): List<KeptListen> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val before = load()
                val after = how(before)
                // Into the file first: a list the file does not hold is not shown as kept.
                if (always || after != before) write(after)
                loaded.value = after
                after
            }
        }

    /** The list as kept, read from the file the first time. Under [lock]. */
    private fun load(): List<KeptListen> = loaded.value ?: decode(
        runCatching { file.takeIf { it.exists() }?.readText() }
            .onFailure { Log.w(TAG, "Could not read the listens kept for later", it) }
            .getOrNull()
    ).also { loaded.value = it }

    /**
     * [list] as the file: written whole beside it, forced onto the disk, and only then given the
     * name. No file at all once the list is empty, so no fingerprint stays longer than it waits.
     */
    private fun write(list: List<KeptListen>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val beside = File(file.path + TEMPORARY)
        try {
            FileOutputStream(beside).use { out ->
                out.write(encode(list).toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!beside.renameTo(file)) throw IOException("Could not put ${file.name} in place")
        } catch (e: Exception) {
            beside.delete()
            throw e
        }
    }

    /** What became of a kept listen once Shazam was asked about it: see [lookUp] and [verdict]. */
    sealed interface Settled {
        /** Named: into the list of everything heard, and off this one. */
        data class Named(val track: Recognised) : Settled

        /** Shazam answered and knew none of it, at any speed: off the list. */
        data object Unknown : Settled

        /**
         * No answer for a reason of the moment, no connection after all or Shazam refusing or down:
         * it stays as it was, and nothing more is asked until the next run.
         */
        data object Unanswered : Settled

        /** An answer that could not be read, or a request refused as it was made: counted. */
        data object Failed : Settled

        /** Counted [MAX_TRIES] times: off the list, as no later run is going to change that. */
        data object GivenUp : Settled
    }

    companion object {
        private const val TAG = "RecogniseLater"

        /** The file, in the app's files that are not backed up. */
        const val FILE = "recognise_later.json"
        private const val TEMPORARY = ".new"
        private const val VERSION = 1

        /** Listens waiting at most: about two hours of Keep listening with no connection. */
        const val MOST = 50

        /** How long a listen waits for a connection: thirty days. */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

        /** Answers that could not be read before a listen is given up. */
        const val MAX_TRIES = 5

        /**
         * What is kept of a window whose request found no connection: [heard], its fingerprint as
         * heard, and in Listen once ([once]) the window put back from the two speeds it tries after
         * a window Shazam knew nothing of ([SpeedRetry.ratesFor]). Keep listening tries no speeds.
         */
        fun printsFor(samples: ShortArray, heard: Fingerprint, once: Boolean): List<Fingerprint> {
            if (!once) return listOf(heard)
            return listOf(heard) + SpeedRetry.ratesFor(0).mapNotNull { rate ->
                runCatching { Fingerprint.of(SpeedRetry.putBack(samples, rate), rate) }.getOrNull()
            }
        }

        /**
         * What Shazam makes of [listen], asked through [ask]: the window as heard, then put back
         * from each speed kept with it, as Listen once asks with a connection. A match from a
         * guessed speed counts only when it fits ([SpeedRetry.fits]), and carries that speed. The
         * first failure ends it: a connection that is not there is not asked again.
         */
        suspend fun lookUp(listen: KeptListen, ask: suspend (Fingerprint) -> RecognitionOutcome): RecognitionOutcome {
            for (print in listen.prints) {
                val outcome = ask(print)
                when {
                    outcome is RecognitionOutcome.Failed -> return outcome
                    outcome !is RecognitionOutcome.Match -> continue
                    print.rate == 1.0 -> return outcome
                    SpeedRetry.fits(outcome.track) ->
                        return RecognitionOutcome.Match(outcome.track.copy(heardAtRate = print.rate))
                }
            }
            return RecognitionOutcome.NoMatch
        }

        /** What [outcome] means for a listen counted [tries] times before. */
        fun verdict(outcome: RecognitionOutcome, tries: Int): Settled = when (outcome) {
            is RecognitionOutcome.Match -> Settled.Named(outcome.track)
            RecognitionOutcome.NoMatch -> Settled.Unknown
            is RecognitionOutcome.Failed -> when {
                ofTheMoment(outcome.reason) -> Settled.Unanswered
                tries + 1 >= MAX_TRIES -> Settled.GivenUp
                else -> Settled.Failed
            }
        }

        /** Whether a failure says nothing about the listen: the connection, or Shazam refusing or down. */
        private fun ofTheMoment(reason: String): Boolean =
            reason == "network" || reason == "http 429" || reason.startsWith("http 5")

        /**
         * Whether Keep listening is keeping what it hears with no connection, once [outcome] has been
         * dealt with ([kept]: it was kept for later): yes from the window that was, no once Shazam
         * answers again, and as it was for silence or a refusal, which say nothing about the
         * connection. What the screen and the notification say in place of "Shazam is not answering".
         */
        fun offlineAfter(was: Boolean, outcome: RecognitionOutcome, kept: Boolean): Boolean = when {
            kept -> true
            outcome !is RecognitionOutcome.Failed -> false
            else -> was
        }

        internal fun kept(list: List<KeptListen>, listen: KeptListen, now: Long): List<KeptListen> =
            fresh(list.filterNot { it.heardAt == listen.heardAt } + listen, now)
                .sortedBy { it.heardAt }
                .takeLast(MOST)

        internal fun fresh(list: List<KeptListen>, now: Long): List<KeptListen> =
            list.filter { now - it.heardAt < KEEP_MS }

        internal fun settled(list: List<KeptListen>, heardAt: Long, verdict: Settled): List<KeptListen> =
            when (verdict) {
                Settled.Unanswered -> list
                Settled.Failed -> list.map { if (it.heardAt == heardAt) it.copy(tries = it.tries + 1) else it }
                else -> list.filterNot { it.heardAt == heardAt }
            }

        internal fun encode(list: List<KeptListen>): String = buildJsonObject {
            put("v", VERSION)
            put("waiting", buildJsonArray {
                list.forEach { listen ->
                    add(buildJsonObject {
                        put("heardAt", listen.heardAt)
                        put("tries", listen.tries)
                        put("prints", buildJsonArray {
                            listen.prints.forEach { print ->
                                add(buildJsonObject {
                                    put("uri", print.uri)
                                    put("ms", print.sampleMs)
                                    put("rate", print.rate)
                                })
                            }
                        })
                    })
                }
            })
        }.toString()

        /** The listens in [text], oldest first, or none when it is not a file this wrote. Never a throw. */
        internal fun decode(text: String?): List<KeptListen> = runCatching {
            val root = Json.parseToJsonElement(text ?: return emptyList()).jsonObject
            if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
            root["waiting"]?.jsonArray.orEmpty()
                .mapNotNull { runCatching { listen(it.jsonObject) }.getOrNull() }
                .sortedBy { it.heardAt }
        }.getOrElse {
            Log.w(TAG, "Could not read the listens kept for later, starting over", it)
            emptyList()
        }

        private fun listen(o: JsonObject): KeptListen? {
            val prints = o["prints"]?.jsonArray.orEmpty().mapNotNull { p ->
                val print = p.jsonObject
                Fingerprint(
                    uri = print["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    sampleMs = print["ms"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    rate = print["rate"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                )
            }
            if (prints.isEmpty()) return null
            return KeptListen(
                heardAt = o["heardAt"]?.jsonPrimitive?.longOrNull ?: return null,
                prints = prints,
                tries = o["tries"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}
