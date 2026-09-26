/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.migration

/**
 * One row of an export, as the file had it.
 *
 * [artists] is already split, because each tool joins several artists its own way and only the
 * reader knows which. The matcher is handed them joined again ([wanted]) or the first alone
 * ([primary]), whichever scores better; see ImportMatcher.
 */
data class ImportedTrack(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val isrc: String? = null,
) {
    val artist: String get() = artists.joinToString(", ")

    fun wanted() = WantedTrack(title, artists.joinToString(" "), durationSeconds)

    /** Only the first artist, for services that list the others and YouTube does not, or the reverse. */
    fun primary() = WantedTrack(title, artists.firstOrNull().orEmpty(), durationSeconds)

    /** Two rows that are the same song as far as matching can tell, so it is searched once. */
    val key: String
        get() = "${normalise(title)}|${artists.joinToString(" ") { normalise(it) }}|${durationSeconds ?: ""}"
}

/** A playlist from the file, its rows in the file's order. */
data class ImportedPlaylist(val name: String, val tracks: List<ImportedTrack>)

enum class ExportFormat(val label: String) {
    /** watsonbox/exportify, served at exportify.app. Artists joined with ", ", commas in a name escaped. */
    EXPORTIFY("Exportify"),

    /** pavelkomarov/exportify, served at exportify.net. Artists joined with ";". */
    EXPORTIFY_NET("Exportify"),
    TUNEMYMUSIC("TuneMyMusic"),
    SOUNDIIZ("Soundiiz"),

    /** File > Export Playlist in Music or iTunes: tab separated, often UTF-16. */
    APPLE_MUSIC("Apple Music"),

    /** File > Library > Export Library: the whole library and its playlists, as XML. See AppleLibraryXml. */
    APPLE_MUSIC_LIBRARY("Apple Music"),
    CSV("CSV"),
}

sealed interface ImportParse {
    data class Parsed(
        val format: ExportFormat,
        val playlists: List<ImportedPlaylist>,
        /** Rows with no title, which cannot be searched for. */
        val skippedRows: Int,
    ) : ImportParse {
        val trackCount: Int get() = playlists.sumOf { it.tracks.size }
    }

    data class Failed(val problem: Problem, val detail: String? = null) : ImportParse

    enum class Problem {
        /** Nothing in the file, or only blank lines. */
        EMPTY,

        /** Not text: a zip, a picture, a spreadsheet saved as .xlsx. */
        NOT_TEXT,

        /** Text, but no column that holds a title. [Failed.detail] carries the header that was read. */
        NO_TITLE_COLUMN,

        /** A list of albums or artists rather than songs, which Soundiiz and TuneMyMusic can also export. */
        NOT_SONGS,

        /** A header and nothing under it with a title. */
        NO_TRACKS,
    }
}

/**
 * Reads the files people already make when they leave a service.
 *
 * A few tools cover almost everyone, and none of them agree on anything but the idea of a title
 * and an artist:
 *
 *  - **Exportify** (Spotify), one file per playlist, in two unrelated versions. exportify.app
 *    (watsonbox) writes "Track Name", "Artist Name(s)", "Album Name", "Track Duration (ms)" and
 *    "ISRC", every field quoted, the header in the language of the browser that ran it, and joins
 *    artists with ", " after escaping any comma inside a name as "\,". exportify.net
 *    (pavelkomarov) writes "Duration (ms)" and no ISRC, and joins artists with ";".
 *  - **TuneMyMusic**, one file for everything: "Track name", "Artist name", "Album",
 *    "Playlist name", "Type", "ISRC". The playlist column is what makes one file several playlists.
 *  - **Soundiiz**: lower case "title", "artist", "album", "isrc", "duration" as "157s", artists
 *    joined with ", ".
 *  - **Apple Music**'s File > Export Playlist: "Name", "Artist", "Album", "Time" in seconds, tab
 *    separated and usually UTF-16, which [decode] deals with.
 *
 * So columns are found by name, ignoring case, rather than by position, and the delimiter is read
 * off the header line, since a spreadsheet that has been through a French Excel writes semicolons.
 * Anything else with a title column still reads, as plain CSV.
 */
object ImportFile {

    // exportify.app translates its header into the browser's language. These are its twelve, from
    // src/i18n/locales/*/translation.json, lower case.
    private val EXPORTIFY_TITLE = listOf(
        "track name", "track-name", "nom du titre", "nombre de la canción", "nome della traccia", "nome da faixa",
        "nummernaam", "låtens namn", "parça adı", "όνομα κομματιού", "トラック名", "اسم الأغنية",
    )
    private val EXPORTIFY_ARTIST = listOf(
        "artist name(s)", "künstlername(n)", "nom(s) de l'artiste", "nombre(s) del artista", "nome dell'artista",
        "nome(s) do artista", "naam van artiest", "artistens namn", "sanatçı adı", "όνομα/τα καλλιτέχνη",
        "アーティスト名", "أسماء الفنانين",
    )
    private val EXPORTIFY_ALBUM = listOf(
        "album name", "album-name", "nom de l'album", "nombre del álbum", "nome dell'album", "nome do álbum",
        "naam van album", "albumets namn", "albüm adı", "όνομα άλμπουμ", "アルバム名", "اسم الألبوم",
    )
    private val EXPORTIFY_DURATION = listOf(
        "track duration (ms)", "track-dauer (ms)", "durée du titre (ms)", "duración de la canción (ms)",
        "durata della traccia (ms)", "duração da faixa (ms)", "nummerduur (ms)", "låtlängd (ms)",
        "parça süresi (ms)", "διάρκεια κομματιού (ms)", "トラックの長さ（ミリ秒）", "مدة الأغنية (بالمللي ثانية)",
    )

    private val TITLE = EXPORTIFY_TITLE + listOf("title", "track title", "song name", "song", "track", "name")
    private val ARTIST = EXPORTIFY_ARTIST + listOf(
        "artist name", "artist names", "artist(s)", "artists", "artist", "album artist",
    )
    private val ALBUM = EXPORTIFY_ALBUM + listOf("album", "album title")
    private val DURATION_MS = EXPORTIFY_DURATION + listOf("duration (ms)", "duration_ms", "duration ms")
    private val DURATION = listOf("duration", "length", "time", "track duration")
    private val ISRC = listOf("isrc")
    private val PLAYLIST = listOf("playlist name", "playlist", "playlist title")

    /** Columns only a list of albums or artists has: Soundiiz's album and artist exports. */
    private val NOT_SONGS = listOf("upc", "nbtracks", "nbfans", "artistid", "albumid")

    /**
     * The file's bytes as text. UTF-8 unless a byte order mark says otherwise, which is how Apple
     * Music's playlist export arrives: UTF-16, and full of NULs if read as anything else.
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> bytes.toString(Charsets.UTF_8)
    }

    /**
     * A playlist name from a file name. Exportify names each file after its playlist, in lower
     * case with underscores for spaces, so "road_trip.csv" is read back as "Road trip".
     */
    fun nameFromFile(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()
        return base.replaceFirstChar { it.uppercase() }.ifEmpty { fileName }
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /**
     * Exportify's Export All, and TuneMyMusic's download of several playlists: a zip with one CSV
     * per playlist, each named after it. Every CSV inside is read as though it had been picked on
     * its own, and one that cannot be read does not sink the others. The macOS resource forks a
     * zip made on a Mac carries are skipped, and so is anything past [maxBytes] unpacked, which is
     * what keeps a hostile archive from filling the phone's memory.
     */
    fun parseArchive(bytes: ByteArray, maxBytes: Int = 50 * 1024 * 1024): ImportParse {
        val playlists = mutableListOf<ImportedPlaylist>()
        var skipped = 0
        var format: ExportFormat? = null
        var budget = maxBytes
        try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (budget > 0) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name.startsWith(".") || "__MACOSX" in entry.name) continue
                    if (!name.endsWith(".csv", ignoreCase = true) && !name.endsWith(".txt", ignoreCase = true)) continue
                    val data = readAtMost(zip, budget)
                    budget -= data.size
                    val parsed = parse(decode(data), nameFromFile(name))
                    if (parsed is ImportParse.Parsed) {
                        playlists += parsed.playlists
                        skipped += parsed.skippedRows
                        if (format == null) format = parsed.format
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (playlists.isEmpty()) return ImportParse.Failed(ImportParse.Problem.NOT_TEXT, e.message)
        }
        if (playlists.isEmpty()) return ImportParse.Failed(ImportParse.Problem.NO_TRACKS)
        return ImportParse.Parsed(format ?: ExportFormat.CSV, playlists, skipped)
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun parse(text: String, fallbackName: String): ImportParse {
        val body = text.removePrefix("\uFEFF")
        if (body.isBlank()) return ImportParse.Failed(ImportParse.Problem.EMPTY)
        // A NUL never appears in a text export and always does in a zip or a picture.
        if (body.take(4096).any { it == '\u0000' }) return ImportParse.Failed(ImportParse.Problem.NOT_TEXT)
        if (AppleLibraryXml.looksLike(body)) return AppleLibraryXml.parse(body, fallbackName)

        val delimiter = delimiterOf(body)
        val rows = readCsv(body, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return ImportParse.Failed(ImportParse.Problem.EMPTY)

        val header = rows.first().map { it.trim().lowercase() }
        fun column(names: List<String>): Int? = names.firstNotNullOfOrNull { name ->
            header.indexOf(name).takeIf { it >= 0 }
        }

        if (NOT_SONGS.any { it in header } && header.none { it in DURATION_MS || it in DURATION }) {
            return ImportParse.Failed(ImportParse.Problem.NOT_SONGS)
        }
        val artist = column(ARTIST)
        val title = column(TITLE)
            // "name" alone is too common a column to trust without an artist beside it.
            ?.takeIf { header[it] != "name" || artist != null }
            ?: return ImportParse.Failed(
                ImportParse.Problem.NO_TITLE_COLUMN,
                rows.first().joinToString(", ") { it.trim() }.take(200),
            )
        val album = column(ALBUM)
        val durationMs = column(DURATION_MS)
        val duration = if (durationMs == null) column(DURATION) else null
        val isrc = column(ISRC)
        val playlist = column(PLAYLIST)

        val format = when {
            durationMs != null && header[durationMs] in EXPORTIFY_DURATION -> ExportFormat.EXPORTIFY
            "artist uri(s)" in header -> ExportFormat.EXPORTIFY
            "artist name(s)" in header -> ExportFormat.EXPORTIFY_NET
            "playlist name" in header && "track name" in header -> ExportFormat.TUNEMYMUSIC
            rows.first().any { it.trim() == "title" } && rows.first().any { it.trim() == "artist" } ->
                ExportFormat.SOUNDIIZ
            delimiter == '\t' && "name" in header && "kind" in header -> ExportFormat.APPLE_MUSIC
            else -> ExportFormat.CSV
        }

        val grouped = LinkedHashMap<String, MutableList<ImportedTrack>>()
        var skipped = 0
        for (row in rows.drop(1)) {
            fun cell(index: Int?): String? = index?.let { row.getOrNull(it) }?.trim()?.ifEmpty { null }
            val name = cell(title)
            if (name == null) {
                skipped++
                continue
            }
            val track = ImportedTrack(
                title = name,
                artists = splitArtists(cell(artist), format),
                album = cell(album),
                durationSeconds = cell(durationMs)?.let { millisToSeconds(it) } ?: cell(duration)?.let { lengthToSeconds(it) },
                isrc = cell(isrc),
            )
            val list = cell(playlist) ?: fallbackName
            grouped.getOrPut(list) { mutableListOf() }.add(track)
        }

        if (grouped.isEmpty()) return ImportParse.Failed(ImportParse.Problem.NO_TRACKS)
        return ImportParse.Parsed(
            format = format,
            playlists = grouped.map { (name, tracks) -> ImportedPlaylist(name, tracks) },
            skippedRows = skipped,
        )
    }

    /**
     * Several artists in one cell, split the way the tool that wrote it joined them.
     *
     * Exportify is the only one that is unambiguous, because it escapes the commas that belong to
     * a name. The others join with a comma and a space, which "Earth, Wind & Fire" also contains;
     * getting that one wrong costs little, since the matcher also scores the whole string joined
     * back together, but it is why " & " and " and " are never treated as separators: those are
     * far more often part of a band's name than a join.
     */
    fun splitArtists(cell: String?, format: ExportFormat): List<String> {
        if (cell.isNullOrBlank()) return emptyList()
        val parts = when (format) {
            ExportFormat.EXPORTIFY -> splitUnescaped(cell, ',').map { it.replace("\\,", ",") }
            // It strips semicolons out of names to be able to join on them, so this is exact.
            ExportFormat.EXPORTIFY_NET -> cell.split(';')
            // Never a slash: that is AC/DC.
            else -> cell.split(Regex("\\s*[,;]\\s*")).let { pieces ->
                // "Tyler, The Creator" and friends: a piece that starts with "The " is the tail of
                // the name before it rather than an artist of its own.
                val joined = mutableListOf<String>()
                for (piece in pieces) {
                    if (joined.isNotEmpty() && piece.trim().startsWith("The ", ignoreCase = true)) {
                        joined[joined.lastIndex] = joined.last() + ", " + piece.trim()
                    } else {
                        joined += piece
                    }
                }
                joined
            }
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun splitUnescaped(text: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] == separator) {
                current.append('\\').append(separator)
                i += 2
                continue
            }
            if (c == separator) {
                out += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun millisToSeconds(text: String): Int? =
        text.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it / 1000.0).let { s -> Math.round(s).toInt() } }

    /**
     * "3:25", "1:02:03", Soundiiz's "157s", or a bare number: seconds when it is small,
     * milliseconds when it is not.
     */
    internal fun lengthToSeconds(text: String): Int? {
        val t = text.trim().removeSuffix("s").trim()
        if (':' in t) {
            val parts = t.split(':').map { it.trim().toIntOrNull() ?: return null }
            return parts.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
        }
        val number = t.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        // No song is ten thousand seconds long, and no song is ten thousand milliseconds long
        // either, so the two readings never overlap in practice.
        return if (number >= 10_000) Math.round(number / 1000.0).toInt() else Math.round(number).toInt()
    }

    /** Comma unless the header line clearly uses something else, which a spreadsheet's CSV may. */
    private fun delimiterOf(body: String): Char {
        val firstLine = StringBuilder()
        var quoted = false
        for (c in body) {
            if (c == '"') quoted = !quoted
            if (!quoted && (c == '\n' || c == '\r')) break
            if (!quoted) firstLine.append(c)
        }
        val line = firstLine.toString()
        return listOf(',', ';', '\t').maxByOrNull { d -> line.count { it == d } }
            ?.takeIf { d -> line.count { it == d } > 0 } ?: ','
    }

    /**
     * RFC 4180, the way the exporters actually write it: fields in double quotes may hold the
     * delimiter, a doubled quote and a line break, and lines may end in CRLF or LF.
     */
    fun readCsv(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    delimiter -> {
                        row += field.toString()
                        field.clear()
                    }
                    // CRLF is one line break, and so is a lone CR, which is what iTunes and Music
                    // still end their exported lines with.
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') Unit else {
                        row += field.toString()
                        field.clear()
                        rows += row
                        row = mutableListOf()
                    }
                    '\n' -> {
                        row += field.toString()
                        field.clear()
                        rows += row
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
