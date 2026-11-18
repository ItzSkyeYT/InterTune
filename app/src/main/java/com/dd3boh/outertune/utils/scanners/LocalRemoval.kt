/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/**
 * Taking a file or a folder of the phone's out of the library from its menu (Unreleased.LOCAL_REMOVE): Remove from
 * library in a local song's menu, Exclude folder in the menu of a folder's row on the Folders tab (his pick of 15
 * Nov 2026, experiments/mocks-1115/1-local-songs, A).
 *
 * Both write the path into Excluded scan paths, the list Settings > Local media > Configure scan locations keeps, so
 * that no scan brings it back, and take the songs out of the library at once. The files are never touched. Every
 * scanner already leaves out a file whose own path is on that list as it does a folder's (getScanFiles by document
 * id, the MediaStore scan by path), and the list shows each line as its path. To bring one back: take its line off
 * the list and scan.
 *
 * A song goes as a YouTube song does with the same words, out of the library and out of the liked songs
 * (SongEntity.toggleLibrary): kept liked, it would still be listed under Songs. A folder's songs go as a scan takes
 * out the songs of an excluded folder, their likes kept. Undo puts back exactly what was there.
 *
 * The part with no Android in it, the addresses and the list, is tested (LocalRemovalTest).
 */
object LocalRemoval {

    /** The system's storage provider, whose folder picker made every address on the list. */
    const val AUTHORITY = "com.android.externalstorage.documents"

    /** A volume of the phone's storage: where it is mounted, and the name the folder picker gives it, "primary" or the card's id. */
    data class Volume(val path: String, val rootId: String)

    /**
     * The document id the folder picker gives [path], "primary:Music/Kiss" for /storage/emulated/0/Music/Kiss, or
     * null for a path on none of [volumes]. The inverse of fileFromUri.
     */
    fun documentId(path: String, volumes: List<Volume>): String? {
        val clean = "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        val volume = volumes
            .filter { val root = it.path.trimEnd('/'); clean == root || clean.startsWith("$root/") }
            .maxByOrNull { it.path.length }
        if (volume != null) return volume.rootId + ":" + clean.removePrefix(volume.path.trimEnd('/')).trimStart('/')
        // Before Android 11 a card is only known by where it is mounted, /storage/<its id>, as fileFromUri reads it back.
        val parts = clean.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[0] == "storage" && parts[1] != "emulated" && parts[1] != "self") {
            return parts[1] + ":" + parts.drop(2).joinToString("/")
        }
        return null
    }

    /** The tree address of [documentId] as the folder picker writes it: content://…/tree/primary%3AMusic%2FKiss. */
    fun treeAddress(documentId: String): String = "content://$AUTHORITY/tree/" + encode(documentId)

    /** The line Excluded scan paths gets for [path], or null for a path on no volume the picker knows. */
    fun addressOf(path: String, volumes: List<Volume>): String? = documentId(path, volumes)?.let(::treeAddress)

    /** The lines of Excluded scan paths as it is stored: one address a line, as stringFromUriList writes it. */
    fun entries(list: String): List<String> = list.split("\n").filter { it.isNotBlank() }.distinct()

    /** [list] with [entry] at the end, once. */
    fun withEntry(list: String, entry: String): String = (entries(list) + entry).distinct().joinToString("\n")

    /** [list] without [entry]; the other lines as they were, in their order. */
    fun withoutEntry(list: String, entry: String): String = entries(list).filterNot { it == entry }.joinToString("\n")

    /** Whether [path] is somewhere inside [folder]: a folder whose name only starts the same is another folder. */
    fun under(path: String, folder: String): Boolean = path.startsWith(folder.trimEnd('/') + "/")

    /** A folder by its own name, "Recordings" for /storage/emulated/0/Recordings/, for what the snackbar says. */
    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** As android.net.Uri.encode, which the picker's addresses are written with: letters, digits and _-!.~'()* stay. */
    internal fun encode(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            if (b < 0x80 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_-!.~'()*")) {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    // ---- on the phone ----

    /** The phone's volumes, as fileFromUri finds them. */
    fun volumes(context: Context): List<Volume> {
        val found = ArrayList<Volume>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storage = context.getSystemService(StorageManager::class.java) ?: return found
            for (volume in storage.storageVolumes) {
                val dir = volume.directory ?: continue
                val id = if (volume.isPrimary) "primary" else volume.uuid ?: continue
                found += Volume(dir.absolutePath, id)
            }
        } else {
            @Suppress("DEPRECATION")
            found += Volume(Environment.getExternalStorageDirectory().absolutePath, "primary")
        }
        return found
    }

    /** The line Excluded scan paths gets for [path] on this phone, or null when it is on no volume the picker knows. */
    fun addressOf(context: Context, path: String): String? = runCatching { addressOf(path, volumes(context)) }.getOrNull()

    /** A song taken out, as it was, for Undo. */
    data class Row(val id: String, val inLibrary: LocalDateTime?, val liked: Boolean, val likedDate: LocalDateTime?)

    /**
     * What one removal changed: the line it added to Excluded scan paths (null when the line was there already, and
     * Undo leaves it), and the songs it took out. [likes] when their likes went with them.
     */
    class Change internal constructor(val entry: String?, val rows: List<Row>, val likes: Boolean)

    private val changed = MutableStateFlow(0)

    /**
     * Goes up after each removal and each Undo: a folder open on screen reads its songs again
     * (LibraryFoldersViewModel), as the Folders tab is a tree read once, not a query that follows the table.
     */
    val changes: StateFlow<Int> = changed

    /** Takes [song], a file of the phone's, out of the library, and its path out of every scan. Off the main thread. */
    suspend fun removeSong(context: Context, database: MusicDatabase, song: SongEntity, address: String): Change {
        val added = addEntry(context, address)
        val row = Row(song.id, song.inLibrary, song.liked, song.likedDate)
        // As SongEntity.toggleLibrary, which a YouTube song's Remove from library writes: the like goes too.
        undoingEntry(context, address, added) { database.transactionNow { toggleInLibrary(song.id, null) } }
        announce()
        return Change(address.takeIf { added }, listOf(row), likes = true)
    }

    /** Takes every song under [folder] out of the library, and the folder out of every scan. Off the main thread. */
    suspend fun excludeFolder(context: Context, database: MusicDatabase, folder: String, address: String): Change {
        // The query's LIKE takes "_" and "%" in a name for any letter; the folder is checked again here.
        val rows = database.localSongsInDirDeep(folder)
            .map { it.song }
            .filter { s -> s.isLocal && s.localPath?.let { under(it, folder) } == true }
            .map { Row(it.id, it.inLibrary, it.liked, it.likedDate) }
        val added = addEntry(context, address)
        // As a scan takes out the songs of an excluded folder (disableLocalSong): their likes stay.
        undoingEntry(context, address, added) { database.transactionNow { rows.forEach { disableLocalSong(it.id) } } }
        announce()
        return Change(address.takeIf { added }, rows, likes = false)
    }

    /** Puts back what [change] did: the songs as they were, and the line off Excluded scan paths if it added it. */
    suspend fun undo(context: Context, database: MusicDatabase, change: Change) {
        change.entry?.let { removeEntry(context, it) }
        database.transactionNow {
            change.rows.forEach { r ->
                if (change.likes) restoreInLibrary(r.id, r.inLibrary, r.liked, r.likedDate)
                else inLibrary(r.id, r.inLibrary)
            }
        }
        announce()
    }

    /**
     * Runs [write], the library's half; if it fails, the line it was for comes off the list again when this removal
     * put it there, so that a refused write leaves everything as it was, and the failure goes on to be said.
     */
    private suspend fun undoingEntry(context: Context, entry: String, added: Boolean, write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            if (added) runCatching { removeEntry(context, entry) }
            throw e
        }
    }

    private suspend fun removeEntry(context: Context, entry: String) {
        context.dataStore.edit { it[ExcludedScanPathsKey] = withoutEntry(it[ExcludedScanPathsKey] ?: "", entry) }
    }

    /** Adds [entry] to Excluded scan paths; false when it was there already. */
    private suspend fun addEntry(context: Context, entry: String): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val list = prefs[ExcludedScanPathsKey] ?: ""
            added = entry !in entries(list)
            if (added) prefs[ExcludedScanPathsKey] = withEntry(list, entry)
        }
        return added
    }

    /** The folder trees kept for the Folders tab are stale now: dropped, and the folders on screen read again. */
    private fun announce() {
        clearDtCache()
        changed.value++
    }
}
