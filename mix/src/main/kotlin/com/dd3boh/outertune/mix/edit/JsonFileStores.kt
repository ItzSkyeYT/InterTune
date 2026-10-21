/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What a file store says when its file cannot be read or an entry is dropped: a line for the log,
 * and what was thrown, if anything. Nothing here can log on its own, the module being pure Kotlin.
 */
typealias StoreProblem = (message: String, cause: Throwable?) -> Unit

/**
 * One small JSON file of the form { "v": 1, "<what>": ... }, read once and written whole at every
 * change: into a file beside it, flushed to the disk, then renamed over it, so that a kill at any
 * moment leaves the old file or the new one and never half of one.
 *
 * A file that cannot be read (damaged, or written by a later build in a form this one does not
 * know) is moved aside to <name>.unreadable, never deleted, and the store starts empty: the
 * listener's edits are not silently lost, and a broken file never stops the music.
 */
internal class JsonFile(private val file: File, private val onProblem: StoreProblem) {

    fun read(field: String): JsonElement? {
        if (!file.exists()) return null
        return try {
            val tree = Json.parseToJsonElement(file.readText()) as? JsonObject ?: throw IOException("not a JSON object")
            val version = (tree["v"] as? JsonPrimitive)?.intOrNull
            if (version != VERSION) throw IOException("file version $version, this build reads $VERSION")
            tree[field] ?: throw IOException("no \"$field\"")
        } catch (e: Exception) {
            val aside = File(file.parentFile, file.name + ".unreadable")
            aside.delete()
            val kept = file.renameTo(aside)
            onProblem("${file.name} could not be read${if (kept) ", kept as ${aside.name}" else ""}; starting empty", e)
            null
        }
    }

    /** Writes [content] under [field]. Throws IOException when the disk refuses, and the old file stays as it was. */
    fun write(field: String, content: JsonElement) {
        val text = Json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("v", VERSION)
            put(field, content)
        })
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot make ${it.path}") }
        val next = File(file.parentFile, file.name + ".new")
        FileOutputStream(next).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        // On Linux, and so on Android, a rename replaces the old file in one step.
        if (!next.renameTo(file)) {
            next.delete()
            throw IOException("cannot replace ${file.path}")
        }
    }

    companion object {
        /** The files' own version, apart from the version inside each spec. */
        const val VERSION = 1
    }
}

/**
 * The saved changes in one JSON file, { "v": 1, "transitions": [ spec, spec, ... ] }, kept in
 * memory once read. An entry this build cannot play (a later spec version, a broken rule) is kept
 * as it was and written back with the others, and [get] gives Auto for it; a save of the same
 * pair replaces it.
 */
class JsonFileTransitionStore(file: File, private val onProblem: StoreProblem = { _, _ -> }) : TransitionStore {

    private val store = JsonFile(file, onProblem)

    /** Every entry as stored, by its pair, in the order they were saved. */
    private var entries: LinkedHashMap<SongPair, JsonElement>? = null

    private fun loaded(): LinkedHashMap<SongPair, JsonElement> = entries ?: LinkedHashMap<SongPair, JsonElement>().also { map ->
        val list = store.read(FIELD)
        if (list != null && list !is JsonArray) onProblem("transitions is not a list; starting empty", null)
        for (entry in list as? JsonArray ?: JsonArray(emptyList())) {
            val pair = TransitionJson.pairOf(entry)
            if (pair == null) onProblem("a saved transition with no pair of songs was dropped", null) else map[pair] = entry
        }
        entries = map
    }

    private fun save(next: LinkedHashMap<SongPair, JsonElement>) {
        store.write(FIELD, JsonArray(next.values.toList()))
        entries = next
    }

    @Synchronized
    override fun get(pair: SongPair): TransitionSpec? = loaded()[pair]?.let(TransitionJson::fromTree)

    @Synchronized
    override fun put(spec: TransitionSpec) {
        val problems = spec.problems()
        require(problems.isEmpty()) { "not a spec to save: ${problems.joinToString("; ")}" }
        val next = LinkedHashMap(loaded())
        // Removed first, so a pair saved again moves to the end, the most recent.
        next.remove(spec.pair)
        next[spec.pair] = TransitionJson.toTree(spec)
        save(next)
    }

    @Synchronized
    override fun delete(pair: SongPair): Boolean {
        if (pair !in loaded()) return false
        save(LinkedHashMap(loaded()).apply { remove(pair) })
        return true
    }

    @Synchronized
    override fun all(): List<TransitionSpec> = loaded().values.mapNotNull(TransitionJson::fromTree)

    @Synchronized
    override fun clear(): Int {
        val count = loaded().size
        if (count > 0) save(LinkedHashMap())
        return count
    }

    companion object {
        private const val FIELD = "transitions"

        /** Where the app keeps it, under its files (not its cache): filesDir/mix/transitions.json. */
        fun at(filesDir: File, onProblem: StoreProblem = { _, _ -> }) =
            JsonFileTransitionStore(File(File(filesDir, "mix"), "transitions.json"), onProblem)
    }
}

/** The fixes to songs' beats in one JSON file, { "v": 1, "fixes": { "<song id>": fix, ... } }. */
class JsonFileBeatFixStore(file: File, private val onProblem: StoreProblem = { _, _ -> }) : BeatFixStore {

    private val store = JsonFile(file, onProblem)

    private var entries: LinkedHashMap<String, JsonElement>? = null

    private fun loaded(): LinkedHashMap<String, JsonElement> = entries ?: LinkedHashMap<String, JsonElement>().also { map ->
        val fixes = store.read(FIELD)
        if (fixes != null && fixes !is JsonObject) onProblem("fixes is not an object; starting empty", null)
        (fixes as? JsonObject)?.let { map.putAll(it) }
        entries = map
    }

    private fun save(next: LinkedHashMap<String, JsonElement>) {
        store.write(FIELD, JsonObject(next))
        entries = next
    }

    @Synchronized
    override fun get(songId: String): BeatFix? = loaded()[songId]?.let(TransitionJson::fix)

    @Synchronized
    override fun put(songId: String, fix: BeatFix) {
        if (fix.isNone) {
            delete(songId)
            return
        }
        require(fix.bpm == null || (fix.bpm.isFinite() && fix.bpm in 20.0..400.0)) { "a tempo of ${fix.bpm}" }
        save(LinkedHashMap(loaded()).apply {
            remove(songId)
            put(songId, TransitionJson.fixTree(fix))
        })
    }

    @Synchronized
    override fun delete(songId: String): Boolean {
        if (songId !in loaded()) return false
        save(LinkedHashMap(loaded()).apply { remove(songId) })
        return true
    }

    @Synchronized
    override fun all(): Map<String, BeatFix> = LinkedHashMap<String, BeatFix>().apply {
        for ((song, tree) in loaded()) TransitionJson.fix(tree)?.let { put(song, it) }
    }

    companion object {
        private const val FIELD = "fixes"

        /** filesDir/mix/beat-fixes.json. */
        fun at(filesDir: File, onProblem: StoreProblem = { _, _ -> }) =
            JsonFileBeatFixStore(File(File(filesDir, "mix"), "beat-fixes.json"), onProblem)
    }
}
