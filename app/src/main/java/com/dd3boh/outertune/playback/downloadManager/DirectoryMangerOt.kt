package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import com.dd3boh.outertune.utils.scanners.fileFromUri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import com.dd3boh.outertune.utils.Toaster

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    var availableFiles: Set<DocumentFile> = mutableSetOf()

    /**
     * Which song each song file is (SongFileNames: an .opus or .m4a whose name holds no id), by its
     * document id: the ones saved by this run, the ones the database links to, the ones whose tags
     * were read (SongFileIds). [notSongs]: the ones whose tags name no song, read once a run.
     */
    private val songFileIds = ConcurrentHashMap<String, String>()
    private val notSongs = ConcurrentHashMap.newKeySet<String>()

    /**
     * The download dirs the last non-cached getAvailableFiles call could not list at all: a
     * volume that is not mounted, a grant that is gone, a provider that failed, or (the main dir
     * itself) doInit having failed outright. Not empty, just not looked at, the same distinction
     * the library scanner draws (ScanMerge.coveredByUnlistedRoot); a listFiles() failure reads as
     * an empty folder with nothing to tell it apart from one whose files were really all deleted.
     */
    var unlistedDirs: List<Uri> = emptyList()
        private set

    /** The extra dirs the last doInit was given, whether or not they could be opened. */
    private var configuredExtraDirs: List<Uri> = emptyList()

    /**
     * Extra dirs from the last doInit that never became a usable DocumentFile: a volume not
     * mounted at launch, a grant that is gone. They are simply left out of allDirs, so without
     * this they never reach the listFilesOrNull check in getAvailableFiles and never get a
     * chance to report themselves as unlisted the way a folder that fails to list later does;
     * every song under one would then read as genuinely deleted by the very next scan.
     */
    var unreadableExtraDirs: List<Uri> = emptyList()
        private set

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager: $dir")
        this.context = context
        this.dir = dir
        configuredExtraDirs = extraDirs
        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            val unreadable = mutableListOf<Uri>()
            for (extraUri in extraDirs.filterNot { it == dir }) {
                val extraDf = documentFileFromUri(context, extraUri)
                if (extraDf != null && extraDf.isDirectory) {
                    newAllDirs.add(extraDf)
                } else {
                    unreadable.add(extraUri)
                }
            }
            allDirs = newAllDirs.toList()
            unreadableExtraDirs = unreadable
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
            unreadableExtraDirs = emptyList()
//            reportException(e)
//            Toaster.show(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG)
            // TODO: snackbar for failed uri or not set up?
        }
    }

    /**
     * Deletes a song's file, from the main download folder only. The list holds the extra import
     * folders' files too, and those folders are promised never to be changed (the storage
     * tooltip), so a song imported from one keeps its file and has to be removed by hand.
     * Synchronized because deletes run in parallel on dlCoroutine, and the read-modify-write of
     * availableFiles below would otherwise lose one, leaving the player a deleted file.
     */
    @Synchronized
    fun deleteFile(mediaId: String): Boolean {
        val file = isExists(mediaId) ?: return false
        if (!isInMainDir(file)) return false
        val deleted = file.delete()
        // Out of the list as well, or the player went on handing out the deleted file for the
        // rest of the session and the song could not be streamed instead.
        if (deleted) {
            availableFiles = availableFiles - file
            documentId(file)?.let { songFileIds.remove(it) }
        }
        return deleted
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val resolver = context.contentResolver
        val directory = DocumentFile.fromTreeUri(context, dir)

        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val fileName = "$displayName [$mediaId].mka"
        val newFile = directory.createFile("audio/mka", fileName) ?: return null

        try {
            // A stream that would not open used to return the new file's address all the same,
            // for an empty file.
            val out = resolver.openOutputStream(newFile.uri) ?: throw IOException("Could not open $fileName")
            out.use { input.copyTo(it) }
            return newFile.uri
        } catch (e: Exception) {
            // Not left behind half written, where the next scan would take it for the download.
            newFile.delete()
            throw e
        }
    }

    /**
     * Saves a download as a song file other players open (SongFiles), called [fileName], with what
     * [write] puts in it. The file is known as [mediaId]'s from now on, though its name does not say
     * so. A failed write leaves nothing behind, as in [saveFile].
     */
    fun saveSongFile(mediaId: String, fileName: String, write: (OutputStream) -> Unit): Uri? {
        val directory = DocumentFile.fromTreeUri(context, dir)
        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }
        // The type Android itself gives the extension: given another, a provider may add the
        // extension of that type to the name, as in "Song.opus.ogg".
        val extension = fileName.substringAfterLast('.')
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val newFile = directory.createFile(mimeType, fileName) ?: return null
        try {
            val out = context.contentResolver.openOutputStream(newFile.uri) ?: throw IOException("Could not open $fileName")
            out.use { stream ->
                val buffered = BufferedOutputStream(stream, 64 * 1024)
                write(buffered)
                buffered.flush()
            }
        } catch (e: Exception) {
            newFile.delete()
            throw e
        }
        documentId(newFile)?.let { songFileIds[it] = mediaId }
        val saved = TreeDocumentFileOt(null, context, newFile.uri, newFile.name ?: fileName, mimeType)
        availableFiles = availableFiles + saved
        return newFile.uri
    }

    private fun documentId(file: DocumentFile): String? =
        runCatching { DocumentsContract.getDocumentId(file.uri) }.getOrNull()

    /**
     * Which song [file] is: for a song file, what is known of it (see [songFileIds]); for any other
     * file, the id in its name, "Title [id].mka", as downloads were always named.
     */
    fun idOf(file: DocumentFile): String? {
        val name = file.name ?: return null
        if (SongFileNames.isSongFile(name)) {
            return documentId(file)?.let { songFileIds[it] } ?: SongFileNames.idInName(name)
        }
        return (file as? TreeDocumentFileOt)?.id ?: name.substringAfterLast('[').substringBeforeLast(']')
    }

    /**
     * Learns which song each song file in [files] is, unless known already: from [knownPaths], the
     * database's file of each downloaded song, then, with Unreleased.DOWNLOAD_FILES, from the file's
     * own tags. A path two songs claim is not taken from the database.
     */
    private fun learnSongFiles(files: List<DocumentFile>, knownPaths: Map<String, String>) {
        for (file in files) {
            val name = file.name ?: continue
            if (!SongFileNames.isSongFile(name)) continue
            val docId = documentId(file) ?: continue
            if (songFileIds.containsKey(docId) || docId in notSongs) continue
            val known = runCatching { fileFromUri(context, file.uri)?.absolutePath }.getOrNull()?.let { knownPaths[it] }
            if (known != null) {
                songFileIds[docId] = known
                continue
            }
            if (!Unreleased.DOWNLOAD_FILES) continue
            val read = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use { SongFileIds.read(name, BufferedInputStream(it, 16 * 1024)) }
            }.getOrNull()
            if (read != null) songFileIds[docId] = read else notSongs += docId
        }
    }

    /** Files found under a folder keep that folder's tree in their address. */
    private fun isInMainDir(file: DocumentFile): Boolean {
        val main = mainDir ?: return false
        return runCatching {
            DocumentsContract.getTreeDocumentId(file.uri) == DocumentsContract.getTreeDocumentId(main.uri)
        }.getOrDefault(false)
    }

    fun isExists(mediaId: String): DocumentFile? {
        return availableFiles.find { idOf(it) == mediaId }
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false, knownPaths(mediaId))
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true, knownPaths: Map<String, String> = emptyMap()): Map<String, Uri> {
        val availableFiles = HashMap<String, Uri>()
        val result = ArrayList<DocumentFile>()
        if (useCache) {
            result.addAll(this.availableFiles.toList())
        } else {
            val unlistable = mutableListOf<Uri>()
            for (d in allDirs) {
                if ((d as? TreeDocumentFileOt)?.listFilesOrNull() == null) {
                    unlistable.add(d.uri)
                    continue
                }
                scanDfRecursive(d, result, true)
            }
            unlistedDirs = unlistedDownloadDirs(
                main = dir.toString(),
                mainOpened = mainDir != null,
                extras = configuredExtraDirs.map { it.toString() },
                unopenedExtras = unreadableExtraDirs.map { it.toString() },
                unlistable = unlistable.map { it.toString() },
            ).map(Uri::parse)
            learnSongFiles(result, knownPaths)
            this.availableFiles = result.toSet()
        }

        for (file in result) {
            val id = idOf(file) ?: continue
            availableFiles.put(id, file.uri)
        }
        return availableFiles
    }

    fun getMainDlStorageUsage(): Long {
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getTotalDlStorageUsage(): Long {
        if (allDirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        availableFiles.sumOf { it.length() }

        return availableFiles.sumOf { it.length() }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }
}

/**
 * Each downloaded song's file as the database has it, to the song's id: how a song file, whose name
 * holds no id, is known again without opening it. A path two songs claim says nothing.
 */
fun knownPaths(songs: List<Song>): Map<String, String> =
    songs.mapNotNull { s -> s.song.localPath?.let { it to s.song.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.distinct().size == 1 }
        .mapValues { it.value.first() }

/**
 * Which download dirs a full listing has to treat as not looked at, rather than as empty.
 *
 * A main folder that is set but could not be opened means nothing was walked, the extra folders
 * included, since doInit gives up on all of them together, so every one of them counts. A blank
 * main folder means nothing is set up (the default, or right after Reset), which is not a folder
 * that failed: counting it would make its unknown path cover every downloaded song, and nothing
 * downloaded could ever be cleared again. Otherwise the extras that could not be opened and the
 * folders that opened but could not be listed.
 */
fun unlistedDownloadDirs(
    main: String,
    mainOpened: Boolean,
    extras: List<String>,
    unopenedExtras: List<String>,
    unlistable: List<String>,
): List<String> = when {
    main.isBlank() -> emptyList()
    !mainOpened -> listOf(main) + extras.filterNot { it == main }
    else -> unopenedExtras + unlistable
}
