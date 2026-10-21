/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.KeyEstimate
import com.dd3boh.outertune.mix.MusicalKey
import com.dd3boh.outertune.mix.RegionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A song's end or opening as Auto knows it ([SongEnd]), as a small JSON text: what the analysis
 * cache keeps beside each song, so a playlist's pills can name Auto's change without the audio.
 * Levels are kept to a tenth of a dB, which is far finer than any rule reads them. What only the
 * audio gives (a beat line fitted afresh over a few bars) is not kept: read back, an end uses its
 * one line throughout.
 */
object SongEndJson {

    /** The form's own version: an entry of another one is not read, and the song is analysed again when it plays. */
    const val VERSION = 1

    private val JSON = Json { prettyPrint = false }

    fun encode(end: SongEnd): String = JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("v", VERSION)
        put("kind", end.kind.name.lowercase())
        put("songMs", end.songMs)
        put("fromMs", end.fromMs)
        put("toMs", end.toMs)
        put("rate", end.sampleRate)
        put("channels", end.channels)
        put("firstSoundMs", end.firstSoundMs)
        put("trueEndMs", end.trueEndMs)
        put("outroStartMs", end.outroStartMs)
        end.introEndMs?.let { put("introEndMs", it) }
        end.fadeMs?.let { put("fadeMs", it) }
        put("medianDb", end.medianDb)
        put("stepMs", end.stepMs)
        put("levelDb", tenths(end.levelDb))
        put("lowDb", tenths(end.lowDb))
        end.bpm?.let { put("bpm", it) }
        put("steadyTempo", end.steadyTempo)
        end.line?.let { l ->
            put("line", buildJsonObject {
                put("t0Ms", l.t0Ms)
                put("beatMs", l.beatMs)
                put("firstK", l.firstK)
                put("lastK", l.lastK)
                put("beatsPerBar", l.beatsPerBar)
                put("phase", l.phase)
                put("beatsTrusted", l.beatsTrusted)
                put("barsTrusted", l.barsTrusted)
                put("barsFromFirstBeat", l.barsFromFirstBeat)
            })
        }
        end.phrases?.let { p ->
            put("phrases", buildJsonObject {
                put("beats", buildJsonArray { p.beats.forEach { add(JsonPrimitive(it)) } })
                put("level", buildJsonArray { p.level.forEach { add(JsonPrimitive(it)) } })
                put("strength", buildJsonArray { p.strength.forEach { add(JsonPrimitive((it * 1000).roundToInt() / 1000.0)) } })
                put("heard", p.heard)
                put("margin", p.margin)
            })
        }
        end.key?.let { k ->
            put("key", buildJsonObject {
                put("tonic", k.key.tonic)
                put("minor", k.key.minor)
                put("correlation", k.correlation)
                put("margin", k.margin)
                put("chroma", buildJsonArray { k.chroma.forEach { add(JsonPrimitive(it)) } })
            })
        }
        put("density", end.density)
        put("trustNote", end.trustNote)
    })

    /** The end in [text], or null for anything of another version or not whole. */
    fun decode(text: String): SongEnd? = runCatching {
        val o = JSON.parseToJsonElement(text).jsonObject
        if (o["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
        fun d(name: String) = o[name]!!.jsonPrimitive.double
        fun dn(name: String) = o[name]?.jsonPrimitive?.doubleOrNull
        val line = (o["line"] as? JsonObject)?.let { l ->
            BeatLine(
                t0Ms = l["t0Ms"]!!.jsonPrimitive.double,
                beatMs = l["beatMs"]!!.jsonPrimitive.double,
                firstK = l["firstK"]!!.jsonPrimitive.int,
                lastK = l["lastK"]!!.jsonPrimitive.int,
                beatsPerBar = l["beatsPerBar"]!!.jsonPrimitive.int,
                phase = l["phase"]!!.jsonPrimitive.int,
                beatsTrusted = l["beatsTrusted"]!!.jsonPrimitive.boolean,
                barsTrusted = l["barsTrusted"]!!.jsonPrimitive.boolean,
                barsFromFirstBeat = l["barsFromFirstBeat"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        val phrases = (o["phrases"] as? JsonObject)?.let { p ->
            PhraseMap(
                beats = p["beats"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                level = p["level"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                strength = p["strength"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
                heard = p["heard"]!!.jsonPrimitive.boolean,
                margin = p["margin"]!!.jsonPrimitive.double,
            )
        }
        val key = (o["key"] as? JsonObject)?.let { k ->
            KeyEstimate(
                MusicalKey(k["tonic"]!!.jsonPrimitive.int, k["minor"]!!.jsonPrimitive.boolean),
                k["correlation"]!!.jsonPrimitive.double,
                k["margin"]!!.jsonPrimitive.double,
                k["chroma"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            )
        }
        SongEnd(
            kind = RegionKind.valueOf(o["kind"]!!.jsonPrimitive.content.uppercase()),
            songMs = d("songMs"),
            fromMs = d("fromMs"),
            toMs = d("toMs"),
            sampleRate = o["rate"]!!.jsonPrimitive.int,
            channels = o["channels"]!!.jsonPrimitive.int,
            firstSoundMs = d("firstSoundMs"),
            trueEndMs = d("trueEndMs"),
            outroStartMs = d("outroStartMs"),
            introEndMs = dn("introEndMs"),
            fadeMs = dn("fadeMs"),
            medianDb = d("medianDb"),
            levelDb = floats(o["levelDb"]!!.jsonArray),
            lowDb = floats(o["lowDb"]!!.jsonArray),
            stepMs = d("stepMs"),
            bpm = dn("bpm"),
            steadyTempo = o["steadyTempo"]!!.jsonPrimitive.boolean,
            line = line,
            phrases = phrases,
            key = key,
            density = d("density"),
            trustNote = o["trustNote"]?.jsonPrimitive?.content ?: "",
        )
    }.getOrNull()

    private fun tenths(values: FloatArray): JsonArray = buildJsonArray {
        // A level of -200 dB stands for silence in every rule, and keeps that value.
        for (v in values) add(JsonPrimitive(if (v <= -200f) -200.0 else (v * 10).roundToInt() / 10.0))
    }

    private fun floats(a: JsonArray): FloatArray = FloatArray(a.size) { a[it].jsonPrimitive.double.toFloat() }
}
