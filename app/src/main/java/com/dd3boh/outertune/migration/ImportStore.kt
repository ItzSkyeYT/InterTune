/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.TreeSet
import java.util.UUID

/**
 * The import under way, written to the app's own files so that it is still there after the app
 * has been closed, or killed for its memory, which Android does to an app nobody is looking at.
 *
 * Two kinds of file, because two things are kept and they could not be less alike.
 *
 * What the chosen file held is written once, when it has been read: its songs with the repeats
 * taken out, and for each playlist the places of its songs among them ([HEAD]). It never changes
 * and is never written again, so nothing that happens later can damage it, and it is the one part
 * that could not be made again: the file itself was read through a permission that ended with
 * the picker.
 *
 * What the lookups came to, and what somebody picked or left out, changes for as long as the
 * import lasts. It is written in blocks of [BLOCK] songs, a file each, and only the blocks in
 * which something changed are written again. A library is thousands of songs with up to four
 * results kept for each: as one file that would be megabytes, written out again every few seconds
 * for an hour. A block is some tens of kilobytes. A block that cannot be read back costs its
 * lookups, which are made again, and nothing else.
 *
 * Every file is written whole under another name and then renamed into place, so a phone that
 * dies in the middle leaves the file that was there, never half of a new one.
 *
 * In files and not in the database: a table would be a migration of the database that carries
 * somebody's whole library, for something that lives an afternoon. Written by hand with
 * kotlinx.serialization's tree, as Announcements reads its own document: this module does not
 * apply the serialization plugin, and its tests stub org.json.
 *
 * One caller at a time. LibraryImport does everything here from a single queue.
 */
class ImportStore(private val dir: File, private val block: Int = BLOCK) {

    /**
     * An import as it was kept. [lostBlocks] is how many blocks of answers were there and could
     * not be read: their songs have no answer, and are looked up again.
     */
    class Kept(
        val fileName: String?,
        val format: ExportFormat,
        val skippedRows: Int,
        val run: ImportRun,
        val lostBlocks: Int,
    )

    /** The run these files are of, and the id they all carry. Nothing is written for any other. */
    private var keeping: ImportRun? = null
    private var id: String? = null

    /** How many files have been put in place. A burst of changes should move it by one, and a test counts. */
    @Volatile
    var written = 0
        private set

    /** Whether an import is kept here. One look at one file, cheap enough to ask as the screen opens. */
    fun holdsOne(): Boolean = File(dir, HEAD).isFile

    /**
     * Starts keeping [run], in place of whatever was kept before. Only what the file held is
     * written: what is found from here on is [save]'s.
     */
    fun begin(fileName: String?, format: ExportFormat, skippedRows: Int, run: ImportRun) {
        clear()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not make the folder ${dir.name}")
        val id = UUID.randomUUID().toString()
        val head = buildJsonObject {
            put("version", VERSION)
            put("import", id)
            fileName?.let { put("file", it) }
            put("format", format.name)
            put("skipped", skippedRows)
            put("tracks", JsonArray(run.tracks.map { it.json() }))
            put("playlists", JsonArray(run.playlists.map { (name, places) ->
                buildJsonObject {
                    put("name", name)
                    put("tracks", JsonArray(places.map { JsonPrimitive(it) }))
                }
            }))
        }
        place(HEAD, head.toString())
        this.id = id
        keeping = run
    }

    /** Whether [run] is the one kept here and has changes that are not written yet. */
    fun owes(run: ImportRun): Boolean = run === keeping && run.hasChanged()

    /**
     * Writes again the blocks of [run] in which something has changed since the last time. A run
     * that is not the one kept here is left alone, changes and all: it is one that Start over or
     * another file has replaced, finishing late.
     */
    fun save(run: ImportRun) {
        if (run !== keeping) return
        val id = id ?: return
        val changed = run.takeChanged()
        if (changed.isEmpty()) return
        try {
            for (number in changed.mapTo(TreeSet()) { it / block }) {
                val from = number * block
                val until = minOf(from + block, run.tracks.size)
                place(blockName(number), blockJson(id, from, run.slots(from, until)).toString())
            }
        } catch (e: Exception) {
            // Still owed, then, and the next save has another go at them.
            run.touch(changed)
            throw e
        }
    }

    /**
     * The import that was kept, or null when there is none or its songs cannot be read back, in
     * which case nothing of it is left behind for the next start to trip over. From here on the
     * run it returns is the one kept, and [save] writes its changes where the old ones are.
     */
    fun load(): Kept? {
        keeping = null
        id = null
        val kept = try {
            read()
        } catch (e: Exception) {
            null
        }
        if (kept == null) clear()
        return kept
    }

    /** Forgets [run] if it is the one kept here: its playlists are made. Another file's import, begun since, stays. */
    fun end(run: ImportRun) {
        if (run === keeping) clear()
    }

    /** Forgets whatever import is kept: Start over, or another file. */
    fun clear() {
        keeping = null
        id = null
        // The head first. Without it nothing here counts as an import, whatever a phone that
        // died at this very line leaves of the rest.
        File(dir, HEAD).delete()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun read(): Kept? {
        val headFile = File(dir, HEAD)
        if (!headFile.isFile) return null
        val head = Json.parseToJsonElement(headFile.readText()) as? JsonObject ?: return null
        // Another build's way of writing it. Reading it by guesswork could put an answer against
        // the wrong song, which is worse than asking for the file again.
        if (head.int("version") != VERSION) return null
        val id = head.string("import") ?: return null
        val tracks = (head["tracks"] as? JsonArray ?: return null).map { trackOf(it as JsonObject) }
        if (tracks.isEmpty()) return null
        val playlists = (head["playlists"] as? JsonArray ?: return null).map { entry ->
            entry as JsonObject
            val places = (entry["tracks"] as JsonArray).map { (it as JsonPrimitive).intOrNull ?: return null }
            if (places.any { it !in tracks.indices }) return null
            (entry.string("name") ?: return null) to places
        }
        val run = ImportRun(tracks, playlists)

        var lost = 0
        for (file in dir.listFiles().orEmpty().sortedBy { it.name }) {
            when {
                file.name == HEAD -> Unit
                // What a write that never finished left under its other name.
                !file.name.startsWith(BLOCK_PREFIX) || !file.name.endsWith(SUFFIX) -> file.delete()
                else -> when (readBlock(file, id, run)) {
                    Read.OURS -> Unit
                    Read.STALE -> file.delete()
                    Read.DAMAGED -> {
                        lost++
                        file.delete()
                    }
                }
            }
        }
        this.id = id
        keeping = run
        return Kept(
            fileName = head.string("file"),
            // A tool this build has no name for is still a list of songs.
            format = ExportFormat.entries.firstOrNull { it.name == head.string("format") } ?: ExportFormat.CSV,
            skippedRows = head.int("skipped") ?: 0,
            run = run,
            lostBlocks = lost,
        )
    }

    private enum class Read { OURS, STALE, DAMAGED }

    /**
     * One block put back into [run]. The whole block is read before any of it is put back, so a
     * block that turns out damaged half way down puts back nothing.
     */
    private fun readBlock(file: File, id: String, run: ImportRun): Read {
        val slots = try {
            val block = Json.parseToJsonElement(file.readText()) as JsonObject
            // Left by an import before this one, which a clear that was cut short did not reach.
            if (block.string("import") != id) return Read.STALE
            val from = block.int("from") ?: return Read.DAMAGED
            val choices = block["choices"] as? JsonObject
            (block["found"] as JsonArray).mapIndexed { offset, found ->
                val index = from + offset
                // An answer that cannot be read is no answer, and the song is looked up again.
                // What was decided about it goes with it: a pick is one of its answer's results.
                val outcome = runCatching { outcomeOf(found) }.getOrNull()
                val choice = if (outcome == null) null else runCatching { choices?.get(index.toString())?.let(::choiceOf) }.getOrNull()
                index to ImportRun.Slot(outcome, choice)
            }
        } catch (e: Exception) {
            return Read.DAMAGED
        }
        slots.forEach { (index, slot) -> run.restore(index, slot) }
        return Read.OURS
    }

    private fun blockJson(id: String, from: Int, slots: List<ImportRun.Slot>) = buildJsonObject {
        put("import", id)
        put("from", from)
        put("found", JsonArray(slots.map { it.outcome.json() }))
        put("choices", JsonObject(buildMap {
            slots.forEachIndexed { offset, slot ->
                when (val choice = slot.choice) {
                    null -> Unit
                    ImportRun.Choice.Skipped -> put((from + offset).toString(), JsonPrimitive(SKIPPED))
                    is ImportRun.Choice.Picked -> put((from + offset).toString(), buildJsonObject { put("picked", choice.song.json()) })
                }
            }
        }))
    }

    /**
     * [text] as the file [name]: written whole beside it, forced onto the disk, and only then
     * given the name, which on this file system replaces the old file in one step.
     */
    private fun place(name: String, text: String) {
        val target = File(dir, name)
        val beside = File(dir, name + TEMPORARY)
        try {
            FileOutputStream(beside).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                // Onto the disk before it takes the name: renamed first, a phone that lost power
                // just after could come back with the name and nothing under it. A disk that will
                // not be hurried is no reason to keep nothing: the file is whole either way.
                runCatching { out.fd.sync() }
            }
            if (!beside.renameTo(target)) throw IOException("Could not put $name in place")
        } catch (e: Exception) {
            beside.delete()
            throw e
        }
        written++
    }

    private fun blockName(number: Int) = "$BLOCK_PREFIX$number$SUFFIX"

    private fun ImportedTrack.json() = buildJsonObject {
        put("title", title)
        put("artists", JsonArray(artists.map { JsonPrimitive(it) }))
        album?.let { put("album", it) }
        durationSeconds?.let { put("seconds", it) }
        isrc?.let { put("isrc", it) }
    }

    private fun trackOf(o: JsonObject) = ImportedTrack(
        title = o.string("title") ?: throw IOException("A song with no title"),
        artists = (o["artists"] as JsonArray).map { (it as JsonPrimitive).content },
        album = o.string("album"),
        durationSeconds = o.int("seconds"),
        isrc = o.string("isrc"),
    )

    private fun Outcome?.json(): JsonElement = when (this) {
        null -> JsonNull
        Outcome.NotFound -> JsonPrimitive(NOT_FOUND)
        is Outcome.Matched -> buildJsonObject { put("matched", best.json()) }
        is Outcome.Review -> buildJsonObject { put("review", JsonArray(candidates.map { it.json() })) }
    }

    /** Null for a track with no answer yet. Anything it cannot read throws, which the caller takes for the same. */
    private fun outcomeOf(found: JsonElement): Outcome? = when {
        found is JsonNull -> null
        found is JsonPrimitive && found.content == NOT_FOUND -> Outcome.NotFound
        found is JsonObject && "matched" in found -> Outcome.Matched(scoredOf(found["matched"] as JsonObject))
        found is JsonObject && "review" in found ->
            Outcome.Review((found["review"] as JsonArray).map { scoredOf(it as JsonObject) }.also { require(it.isNotEmpty()) })
        else -> throw IOException("An answer of no known kind")
    }

    private fun choiceOf(decided: JsonElement): ImportRun.Choice = when {
        decided is JsonPrimitive && decided.content == SKIPPED -> ImportRun.Choice.Skipped
        decided is JsonObject -> ImportRun.Choice.Picked(songOf(decided["picked"] as JsonObject))
        else -> throw IOException("A decision of no known kind")
    }

    private fun Scored.json() = buildJsonObject {
        put("song", candidate.json())
        put("title", title)
        put("artist", artist)
        put("duration", duration)
        put("total", total)
        put("length", lengthCompared)
    }

    private fun scoredOf(o: JsonObject) = Scored(
        candidate = songOf(o["song"] as JsonObject),
        title = o.number("title"),
        artist = o.number("artist"),
        duration = o.number("duration"),
        total = o.number("total"),
        // Absent reads as not compared, which sends a doubt to review and never past it.
        lengthCompared = (o["length"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )

    /**
     * A search result with what the import shows of it and what a playlist needs of it. Its
     * endpoint is not kept: the search gives none, and nothing here plays a song.
     */
    private fun SongItem.json() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("artists", JsonArray(artists.map { artist ->
            buildJsonObject {
                put("name", artist.name)
                artist.id?.let { put("id", it) }
            }
        }))
        album?.let {
            put("album", buildJsonObject {
                put("name", it.name)
                put("id", it.id)
            })
        }
        duration?.let { put("duration", it) }
        put("thumbnail", thumbnail)
        if (explicit) put("explicit", true)
        setVideoId?.let { put("setVideoId", it) }
    }

    private fun songOf(o: JsonObject) = SongItem(
        id = o.string("id") ?: throw IOException("A result with no id"),
        title = o.string("title") ?: throw IOException("A result with no title"),
        artists = (o["artists"] as JsonArray).map {
            it as JsonObject
            Artist(name = it.string("name") ?: throw IOException("An artist with no name"), id = it.string("id"))
        },
        album = (o["album"] as? JsonObject)?.let {
            Album(name = it.string("name") ?: throw IOException("An album with no name"), id = it.string("id") ?: throw IOException("An album with no id"))
        },
        duration = o.int("duration"),
        thumbnail = o.string("thumbnail").orEmpty(),
        explicit = (o["explicit"] as? JsonPrimitive)?.booleanOrNull ?: false,
        setVideoId = o.string("setVideoId"),
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.number(key: String): Double =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw IOException("No number under $key")

    companion object {
        /** A hundred songs to a file: a write of some tens of kilobytes, and a library of ten thousand is a hundred files. */
        const val BLOCK = 100

        /** Of the way these files are written. One that says another number is not read. */
        private const val VERSION = 1
        private const val HEAD = "import.json"
        private const val BLOCK_PREFIX = "found-"
        private const val SUFFIX = ".json"
        private const val TEMPORARY = ".tmp"
        private const val NOT_FOUND = "none"
        private const val SKIPPED = "skipped"
    }
}
