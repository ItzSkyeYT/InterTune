package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.IOException
import java.io.InputStream

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    var availableFiles: Set<DocumentFile> = mutableSetOf()

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
//            Toast.makeText(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG).show()
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
        if (deleted) availableFiles = availableFiles - file
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

    /** Files found under a folder keep that folder's tree in their address. */
    private fun isInMainDir(file: DocumentFile): Boolean {
        val main = mainDir ?: return false
        return runCatching {
            DocumentsContract.getTreeDocumentId(file.uri) == DocumentsContract.getTreeDocumentId(main.uri)
        }.getOrDefault(false)
    }

    fun isExists(mediaId: String): DocumentFile? {
        return availableFiles.find { (it as TreeDocumentFileOt).id == mediaId }
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        return isExists(mediaId)?.uri
    }

    fun getMissingFiles(mediaId: List<Song>): List<Song> {
        val missingFiles = mediaId.toMutableSet()
        val result = getAvailableFiles(false)
        missingFiles.removeIf { f -> result.any { it.key == f.id } }
        return missingFiles.toList()
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
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
            this.availableFiles = result.toSet()
        }

        for (file in result) {
            val path = file.name ?: continue
            availableFiles.put(path.substringAfterLast('[').substringBeforeLast(']'), file.uri)
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
