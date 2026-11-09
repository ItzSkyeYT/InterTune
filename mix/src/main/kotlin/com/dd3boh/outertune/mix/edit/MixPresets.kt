/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.max

/**
 * A change's shape kept under a name, to lay on any pair of songs in one tap (his ask of 9 Nov
 * 2026: "let me create saved presets for the transitions"), as DJ apps and DAWs keep a listener's
 * own presets beside their own (Ableton's Save Preset, saved FX in Serato and rekordbox). It keeps
 * what shapes the change and not the songs: the length, each lane's move, curves and mutes, beat
 * matching and the song it adjusts, the loops by their bars and where they sit against the
 * change's bar 1. Never where bar 1 falls in either song, a song's beat fix, or a later start in B.
 */
data class MixPreset(val id: String, val name: String, val shape: TransitionSpec) {

    /**
     * [onto] shaped as this preset, on [lanes] (the page's: a preset made on parts is laid on bands
     * where the songs have none, as the engine plays it there, and one made on bands on parts): its
     * songs, where bar 1 falls in each, their fixes and its start in B as they were. The length is
     * the preset's; where bar 1 falls in A for it is the caller's to work out (the editor keeps the
     * change's end in A, as its length sheet does).
     */
    fun laidOn(onto: TransitionSpec, lanes: LaneSet = onto.lanes): TransitionSpec {
        val s = when {
            shape.lanes == lanes -> shape
            lanes == LaneSet.PARTS -> Presets.onParts(shape)
            else -> Presets.onBands(shape)
        }
        // The loops where they sit against bar 1, in each song's own bars.
        val introLoop = s.introLoop?.let { l -> l.copy(fromMs = max(0.0, onto.b.atMs + (l.fromMs - s.b.atMs) / s.b.barMs * onto.b.barMs)) }
        val outroLoop = s.outroLoop?.let { l -> l.copy(atMs = max(0.0, onto.a.atMs + (l.atMs - s.a.atMs) / s.a.barMs * onto.a.barMs)) }
        return onto.copy(
            lanes = s.lanes, bars = s.bars, seconds = s.seconds, moves = s.moves, curves = s.curves, muted = s.muted,
            beatMatch = s.beatMatch, matchSide = s.matchSide, introTwice = s.introTwice, introLoop = introLoop, outroLoop = outroLoop,
            plain = false, from = Preset.CUSTOM,
        )
    }

    /** Whether [spec] is shaped as this preset, as laid on its own songs: its chip is lit. */
    fun shapes(spec: TransitionSpec): Boolean = !spec.plain && laidOn(spec).copy(preset = spec.preset, from = spec.from) == spec

    companion object {
        /** The songs a preset's shape stands on, which are none: valid ids, never a song's. */
        const val SONG_A = "preset:a"
        const val SONG_B = "preset:b"

        /** [spec]'s shape, its songs left out. */
        fun shapeOf(spec: TransitionSpec): TransitionSpec = spec.copy(
            a = spec.a.copy(song = SONG_A, source = "", analysis = 0, fix = BeatFix.NONE),
            b = spec.b.copy(song = SONG_B, source = "", analysis = 0, fix = BeatFix.NONE),
            bStartMs = 0.0, plain = false, savedAt = 0, from = Preset.CUSTOM,
        )
    }
}

/** The listener's presets, in the order they were made. */
interface MixPresetStore {
    fun all(): List<MixPreset>

    /** Adds [preset], or replaces the one with its id where there is one, in its place. */
    fun put(preset: MixPreset)

    fun delete(id: String): Boolean
}

/**
 * The presets in one JSON file beside the saved changes, { "v": 1, "presets": [ { "id", "name",
 * "spec" }, ... ] }, in the app's files, so an update keeps them. Written as the saved changes are
 * ([JsonFile]): whole, beside the old file, then renamed over it; a file that cannot be read is
 * moved aside, never lost. A preset this build cannot read is dropped from the list shown, and kept
 * in the file until the list is next written.
 */
class JsonFileMixPresetStore(file: File, private val onProblem: StoreProblem = { _, _ -> }) : MixPresetStore {

    private val store = JsonFile(file, onProblem)
    private var entries: MutableList<JsonElement>? = null

    private fun loaded(): MutableList<JsonElement> = entries ?: ArrayList<JsonElement>().also { list ->
        val read = store.read(FIELD)
        if (read != null && read !is JsonArray) onProblem("presets is not a list; starting empty", null)
        (read as? JsonArray)?.let { list.addAll(it) }
        entries = list
    }

    private fun save(next: MutableList<JsonElement>) {
        store.write(FIELD, JsonArray(next.toList()))
        entries = next
    }

    @Synchronized
    override fun all(): List<MixPreset> = loaded().mapNotNull(::presetOf)

    @Synchronized
    override fun put(preset: MixPreset) {
        val problems = preset.shape.problems()
        require(problems.isEmpty()) { "not a preset to save: ${problems.joinToString("; ")}" }
        require(preset.id.isNotBlank() && preset.name.isNotBlank()) { "a preset needs an id and a name" }
        val tree = buildJsonObject {
            put("id", preset.id)
            put("name", preset.name)
            put("spec", TransitionJson.toTree(preset.shape))
        }
        val next = ArrayList(loaded())
        val at = next.indexOfFirst { idOf(it) == preset.id }
        if (at >= 0) next[at] = tree else next += tree
        save(next)
    }

    @Synchronized
    override fun delete(id: String): Boolean {
        val next = ArrayList(loaded())
        if (!next.removeAll { idOf(it) == id }) return false
        save(next)
        return true
    }

    private fun idOf(e: JsonElement): String? = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

    private fun presetOf(e: JsonElement): MixPreset? {
        val o = e as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val shape = o["spec"]?.let(TransitionJson::fromTree) ?: return null
        return MixPreset(id, name, shape)
    }

    companion object {
        private const val FIELD = "presets"

        /** Under the app's files, beside the saved changes (mix/transitions.json). */
        const val FILE = "mix/presets.json"

        fun at(filesDir: File, onProblem: StoreProblem = { _, _ -> }) = JsonFileMixPresetStore(File(filesDir, FILE), onProblem)
    }
}
