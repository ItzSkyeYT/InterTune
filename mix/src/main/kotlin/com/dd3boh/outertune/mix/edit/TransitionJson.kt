/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The stored form of a [TransitionSpec], one small JSON object:
 *
 * ```json
 * { "v": 1, "lanes": "bands", "bars": 8, "seconds": 0.0, "beatMatch": true,
 *   "preset": "custom", "from": "fade", "plain": false,
 *   "a": { "song": "<id>", "at": 281340.0, "bpm": 111.0, "beats": 4, "source": "251:4820311:312", "analysis": 1,
 *          "fix": { "bpm": null, "downbeatShift": 0, "nudgeMs": 0, "source": "" } },
 *   "b": { "song": "<id>", "at": 1210.0, "bpm": 109.0, "beats": 4, "source": "251:5791102:312", "analysis": 1, "fix": { ... } },
 *   "moves": { "A.lows": { "u": 3.75, "len": 0.25, "how": "fade" }, "B.highs": { "u": 0.0, "len": 2.0, "how": "thin" }, ... },
 *   "curves": { "A.mids": { "gain": [[4.0, 1.0], [6.0, 0.0]], "lp": [[4.0, 20000.0], [6.0, 600.0]], "hp": [] } },
 *   "muted": [], "introTwice": 0, "bStart": 0.0, "savedAt": 1792600000000 }
 * ```
 *
 * `u` counts bars from the start of bar 1, as stems/tools/render.py and the 19 Oct mock do, so a
 * spec saved on a phone renders on the PC with the same tools. The song ids are the songs' own and
 * no title is ever written. Keys this build does not know are passed over, so a newer build of
 * the same version can add fields; a spec of another version, or one that breaks a rule
 * ([TransitionSpec.problems]), is not read at all and the pair plays Auto.
 *
 * Written by hand on kotlinx.serialization's JSON tree rather than generated, so that every rule
 * is checked in one place and a store can keep an entry it cannot read as it was.
 */
object TransitionJson {

    private val json = Json { prettyPrint = false }

    fun encode(spec: TransitionSpec): String = json.encodeToString(JsonElement.serializer(), toTree(spec))

    /** The spec in [text], or null when it is not one this build can play: Auto plays instead. */
    fun decode(text: String): TransitionSpec? = runCatching { json.parseToJsonElement(text) }.getOrNull()?.let(::fromTree)

    internal fun toTree(spec: TransitionSpec): JsonObject = buildJsonObject {
        put("v", spec.version)
        put("lanes", spec.lanes.key)
        put("bars", spec.bars)
        put("seconds", spec.seconds)
        put("beatMatch", spec.beatMatch)
        put("preset", spec.preset.key)
        put("from", spec.from.key)
        put("plain", spec.plain)
        put("a", anchorTree(spec.a))
        put("b", anchorTree(spec.b))
        put("moves", buildJsonObject {
            // In the lane set's own order, so two saves of the same spec are the same text.
            val ordered = spec.lanes.ids.filter { it in spec.moves } + (spec.moves.keys - spec.lanes.ids.toSet())
            for (id in ordered) {
                val move = spec.moves.getValue(id)
                put(id.key, buildJsonObject {
                    put("u", move.u)
                    put("len", move.len)
                    put("how", move.how.key)
                })
            }
        })
        put("curves", buildJsonObject {
            for ((id, curve) in spec.curves.entries.sortedBy { it.key.key }) {
                put(id.key, buildJsonObject {
                    put("gain", keysTree(curve.gain))
                    put("lp", keysTree(curve.lowPassHz))
                    put("hp", keysTree(curve.highPassHz))
                })
            }
        })
        put("muted", buildJsonArray { spec.muted.map { it.key }.sorted().forEach { add(JsonPrimitive(it)) } })
        put("introTwice", spec.introTwice)
        put("bStart", spec.bStartMs)
        put("savedAt", spec.savedAt)
    }

    /** The spec in [tree], or null for another version, a missing or misspelled field, or a broken rule. */
    internal fun fromTree(tree: JsonElement): TransitionSpec? = runCatching {
        val o = tree as? JsonObject ?: return null
        val version = o.int("v") ?: return null
        if (version != TransitionSpec.VERSION) return null
        val moves = (o["moves"] as? JsonObject ?: return null).mapKeys { (key, _) -> LaneId.of(key) ?: return null }
            .mapValues { (_, m) ->
                val mo = m as? JsonObject ?: return null
                Move(mo.double("u") ?: return null, mo.double("len") ?: return null, How.of(mo.string("how") ?: return null) ?: return null)
            }
        val curves = (o["curves"]?.let { it as? JsonObject ?: return null } ?: JsonObject(emptyMap())).mapKeys { (key, _) -> LaneId.of(key) ?: return null }
            .mapValues { (_, c) ->
                val co = c as? JsonObject ?: return null
                LaneCurve(
                    gain = keys(co["gain"]) ?: return null,
                    lowPassHz = keys(co["lp"] ?: JsonArray(emptyList())) ?: return null,
                    highPassHz = keys(co["hp"] ?: JsonArray(emptyList())) ?: return null,
                )
            }
        val muted = (o["muted"]?.let { it as? JsonArray ?: return null } ?: JsonArray(emptyList())).map { LaneId.of((it as? JsonPrimitive)?.content ?: return null) ?: return null }.toSet()
        val preset = Preset.of(o.string("preset") ?: return null) ?: return null
        val spec = TransitionSpec(
            a = anchor(o["a"]) ?: return null,
            b = anchor(o["b"]) ?: return null,
            lanes = LaneSet.of(o.string("lanes") ?: return null) ?: return null,
            bars = o.int("bars") ?: return null,
            seconds = o.double("seconds") ?: 0.0,
            beatMatch = o.boolean("beatMatch") ?: return null,
            preset = preset,
            from = o.string("from")?.let { Preset.of(it) ?: return null } ?: preset,
            plain = o.boolean("plain") ?: false,
            moves = moves,
            curves = curves,
            muted = muted,
            introTwice = o.int("introTwice") ?: 0,
            bStartMs = o.double("bStart") ?: 0.0,
            savedAt = o.long("savedAt") ?: 0L,
            version = version,
        )
        spec.takeIf { it.isValid }
    }.getOrNull()

    /** The pair a stored entry is for, read without reading the rest: so an entry this build cannot play can still be found and kept. */
    internal fun pairOf(tree: JsonElement): SongPair? {
        val o = tree as? JsonObject ?: return null
        val a = (o["a"] as? JsonObject)?.string("song") ?: return null
        val b = (o["b"] as? JsonObject)?.string("song") ?: return null
        return SongPair(a, b)
    }

    internal fun fixTree(fix: BeatFix): JsonObject = buildJsonObject {
        put("bpm", fix.bpm)
        put("downbeatShift", fix.downbeatShift)
        put("nudgeMs", fix.nudgeMs)
        put("source", fix.source)
        fix.oneMs?.let { put("oneMs", it) }
    }

    internal fun fix(tree: JsonElement?): BeatFix? {
        val o = tree as? JsonObject ?: return null
        val bpm = when (val b = o["bpm"]) {
            null, JsonNull -> null
            else -> (b as? JsonPrimitive)?.doubleOrNull ?: return null
        }
        return BeatFix(
            bpm = bpm,
            downbeatShift = o.int("downbeatShift") ?: 0,
            nudgeMs = o.int("nudgeMs") ?: 0,
            source = o.string("source") ?: "",
            oneMs = o.double("oneMs")?.takeIf { it.isFinite() && it >= 0 },
        )
    }

    private fun anchorTree(a: Anchor): JsonObject = buildJsonObject {
        put("song", a.song)
        put("at", a.atMs)
        put("bpm", a.bpm)
        put("beats", a.beatsPerBar)
        put("source", a.source)
        put("analysis", a.analysis)
        put("fix", fixTree(a.fix))
    }

    private fun anchor(tree: JsonElement?): Anchor? {
        val o = tree as? JsonObject ?: return null
        return Anchor(
            song = o.string("song") ?: return null,
            atMs = o.double("at") ?: return null,
            bpm = o.double("bpm") ?: return null,
            beatsPerBar = o.int("beats") ?: 4,
            source = o.string("source") ?: "",
            analysis = o.int("analysis") ?: 0,
            fix = o["fix"]?.let { fix(it) ?: return null } ?: BeatFix.NONE,
        )
    }

    private fun keysTree(keys: List<Key>) = buildJsonArray {
        for (k in keys) add(buildJsonArray { add(JsonPrimitive(k.u)); add(JsonPrimitive(k.value)) })
    }

    private fun keys(tree: JsonElement?): List<Key>? {
        val array = tree as? JsonArray ?: return null
        return array.map { point ->
            val pair = point as? JsonArray ?: return null
            if (pair.size != 2) return null
            Key(
                (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return null,
                (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return null,
            )
        }
    }

    // Each takes the field only when it is a JSON value of that kind: a number written as a
    // string ("8") is not a number, and the spec is not read.
    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive
    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.isString }?.content
    private fun JsonObject.double(name: String): Double? = primitive(name)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.int(name: String): Int? = primitive(name)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.long(name: String): Long? = primitive(name)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.boolean(name: String): Boolean? = primitive(name)?.takeIf { !it.isString }?.booleanOrNull
}

/** This spec in its stored form. */
fun TransitionSpec.toJson(): String = TransitionJson.encode(this)

/** The spec stored in [json], or null when this build cannot play it (another version, a broken rule): Auto plays. */
fun transitionSpecOf(json: String): TransitionSpec? = TransitionJson.decode(json)
