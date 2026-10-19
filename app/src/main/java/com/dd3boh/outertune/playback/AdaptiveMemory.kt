/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.Features
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What the last few radio runs had played, kept in a file of its own so a force close does not
 * take it (AdaptiveQueue.Heard holds it in memory).
 *
 * Without it, a radio reopened after the app was killed showed the songs it had already played as
 * still to come, folded behind "N more songs waiting", and the planner and the radio's next page
 * started from nothing, so songs already heard could come round again.
 *
 * Saved rather than rebuilt from the listening history of the queue: a song skipped before it
 * started has no row there, no row is written while casting or while history is paused, a back
 * press and a skip both read as skipped, and the picks, their marks and the songs put in by hand
 * leave no trace in the log at all.
 *
 * A run's [Evidence], what it learned of the sitting, comes back only within the engine's run
 * window ([fresh]); what it played, the songs put in by hand and the picks always do. Nothing here
 * decides anything on its own: a run applies only to the queue and run it names. Text that does not
 * parse gives nothing, never a throw, and is overwritten by the next save. No Android in here, so it
 * is tested.
 */
object AdaptiveMemory {

    /** The file's name, in the app's own files. */
    const val FILE = "adaptive_queue.json"

    /** Runs kept: the one playing and the three before it. */
    const val RUNS = AdaptiveQueue.EARLIER_RUNS + 1

    /** Songs reached and versions kept per run, newest: more than a day of listening. */
    const val KEEP = 500

    private const val VERSION = 1

    /** One song of a run as heard. */
    data class Entry(
        val id: String,
        val title: String?,
        val artists: List<String>,
        val durationSec: Int,
        val kept: Boolean = false,
        val skipped: Boolean = false,
        val liked: Boolean = false,
        val replay: Boolean = false,
        val removed: Boolean = false,
    )

    /** What a run learned of the sitting: the songs as heard, newest first, and what they taught. */
    data class Evidence(
        val recent: List<Entry> = emptyList(),
        val skipsByArtist: Map<String, Int> = emptyMap(),
        val skippedSongs: List<String> = emptyList(),
        val positiveArtists: List<String> = emptyList(),
        val start: Entry? = null,
        val lastLeave: AdaptiveQueue.Leave? = null,
    )

    /** One radio run as saved, at [savedAt]. Lists are oldest first, as the run keeps them. */
    data class Run(
        val key: String,
        val queueId: Long,
        val runId: Long,
        val savedAt: Long,
        val reached: List<String>,
        val versions: List<String> = emptyList(),
        val byHand: List<String> = emptyList(),
        val picks: Map<String, String> = emptyMap(),
        val playing: String? = null,
        val played: Int = 0,
        val evidence: Evidence? = null,
        val note: AdaptiveNote.Note? = null,
    )

    /**
     * [run] as it may be restored at [now]: over the engine's run window old, it keeps what it
     * played, its songs put in by hand and its picks, and leaves its evidence and its note behind.
     * Both describe a sitting that is over.
     */
    fun fresh(run: Run, now: Long): Run =
        if (now - run.savedAt <= Features.RUN_WINDOW_MS) run else run.copy(evidence = null, note = null)

    /**
     * The file's text for [runs]. Without [withEvidence], while listening history is paused, the
     * skips and keeps stay out: pausing history means nothing new about the listener is kept, and
     * what the queue played is the queue's own state, saved with it anyway.
     */
    fun encode(runs: List<Run>, withEvidence: Boolean = true): String = buildJsonObject {
        put("v", VERSION)
        put("runs", buildJsonArray { runs.take(RUNS).forEach { add(run(it, withEvidence)) } })
    }.toString()

    /** The runs in [text], or none when it is not a file this wrote. */
    fun decode(text: String): List<Run> = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
        root["runs"]?.jsonArray.orEmpty().mapNotNull { runCatching { run(it.jsonObject) }.getOrNull() }.take(RUNS)
    }.getOrDefault(emptyList())

    private fun run(r: Run, withEvidence: Boolean): JsonObject = buildJsonObject {
        put("key", r.key)
        put("queue", r.queueId)
        put("run", r.runId)
        put("at", r.savedAt)
        put("reached", strings(r.reached.takeLast(KEEP)))
        put("versions", strings(r.versions.takeLast(KEEP)))
        put("byHand", strings(r.byHand.takeLast(KEEP)))
        put("picks", buildJsonObject { r.picks.forEach { (id, seed) -> put(id, seed) } })
        r.playing?.let { put("playing", it) }
        put("played", r.played)
        if (withEvidence) r.evidence?.let { e ->
            put("evidence", buildJsonObject {
                put("recent", buildJsonArray { e.recent.forEach { add(entry(it)) } })
                put("skips", buildJsonObject { e.skipsByArtist.forEach { (a, n) -> put(a, n) } })
                put("songs", strings(e.skippedSongs))
                put("positive", strings(e.positiveArtists))
                e.start?.let { put("start", entry(it)) }
                e.lastLeave?.let { put("left", it.name) }
            })
        }
        r.note?.let { n ->
            put("note", buildJsonObject {
                put("kind", n.kind.code)
                put("count", n.count)
                n.title?.let { put("title", it) }
                put("picks", strings(n.picks.toList()))
                put("setAt", n.setAt)
            })
        }
    }

    private fun run(o: JsonObject): Run = Run(
        key = o.string("key")!!,
        queueId = o["queue"]!!.jsonPrimitive.longOrNull!!,
        runId = o["run"]!!.jsonPrimitive.longOrNull!!,
        savedAt = o["at"]!!.jsonPrimitive.longOrNull!!,
        reached = o.strings("reached"),
        versions = o.strings("versions"),
        byHand = o.strings("byHand"),
        picks = (o["picks"] as? JsonObject).orEmpty().mapNotNull { (id, seed) -> seed.text()?.let { id to it } }.toMap(),
        playing = o.string("playing"),
        played = o["played"]?.jsonPrimitive?.intOrNull ?: 0,
        evidence = (o["evidence"] as? JsonObject)?.let { e ->
            Evidence(
                recent = (e["recent"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::entry) },
                skipsByArtist = (e["skips"] as? JsonObject).orEmpty()
                    .mapNotNull { (a, n) -> (n as? JsonPrimitive)?.intOrNull?.let { a to it } }.toMap(),
                skippedSongs = e.strings("songs"),
                positiveArtists = e.strings("positive"),
                start = (e["start"] as? JsonObject)?.let(::entry),
                lastLeave = e.string("left")?.let { name -> AdaptiveQueue.Leave.entries.firstOrNull { it.name == name } },
            )
        },
        note = (o["note"] as? JsonObject)?.let { n ->
            AdaptiveNote.Kind.ofCode(n.string("kind"))?.let { kind ->
                AdaptiveNote.Note(
                    kind = kind,
                    count = n["count"]?.jsonPrimitive?.intOrNull ?: 0,
                    title = n.string("title"),
                    picks = n.strings("picks").toSet(),
                    setAt = n["setAt"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
        },
    )

    private fun entry(e: Entry): JsonObject = buildJsonObject {
        put("id", e.id)
        e.title?.let { put("title", it) }
        put("artists", strings(e.artists))
        put("length", e.durationSec)
        if (e.kept) put("kept", true)
        if (e.skipped) put("skipped", true)
        if (e.liked) put("liked", true)
        if (e.replay) put("replay", true)
        if (e.removed) put("removed", true)
    }

    private fun entry(o: JsonObject): Entry? {
        val id = o.string("id") ?: return null
        return Entry(
            id = id,
            title = o.string("title"),
            artists = o.strings("artists"),
            durationSec = o["length"]?.jsonPrimitive?.intOrNull ?: -1,
            kept = o.flag("kept"),
            skipped = o.flag("skipped"),
            liked = o.flag("liked"),
            replay = o.flag("replay"),
            removed = o.flag("removed"),
        )
    }

    private fun strings(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.string(name: String): String? = this[name]?.takeIf { it != JsonNull }?.text()

    private fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray).orEmpty().mapNotNull { it.text() }

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
