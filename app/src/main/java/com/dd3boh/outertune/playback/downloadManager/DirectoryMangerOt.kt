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
import com.dd3boh.outertune.playback.FolderFileCheck
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import com.dd3boh.outertune.utils.scanners.fileFromUri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Whether the main folder is not set, open, or saved but not opened (DownloadFolderState), as
     * the last doInit found it, so again after each change of folder (DownloadUtil.cd). The Storage
     * page shows it on the folder's row, and the move after a download says it once. Before the
     * init below, which sets it.
     */
    private val _folderState = MutableStateFlow(DownloadFolderState.NOT_SET)
    val folderState: StateFlow<DownloadFolderState> = _folderState.asStateFlow()

    init {
        doInit(context, dir, extraDirs)
    }

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager: $dir")
        this.context = context
        this.dir = dir
        configuredExtraDirs = extraDirs
        // No folder of the person's own (the default, or Inside the app chosen): downloads stay
        // inside the app, and the extra folders are read all the same. They used to go with the
        // main folder, and the next start's scan took every song in them out of Downloads.
        if (dir.toString().isBlank()) {
            mainDir = null
            val (opened, unreadable) = openExtras(context, extraDirs)
            allDirs = opened
            unreadableExtraDirs = unreadable
            _folderState.value = DownloadFolderState.of(pathSaved = false, opened = false)
            Log.i(TAG, "No download folder of its own, ${allDirs.size} extra folders read")
            return
        }
        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val (extras, unreadable) = openExtras(context, extraDirs.filterNot { it == dir })
            allDirs = listOf(mainDir!!) + extras
            unreadableExtraDirs = unreadable
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
            _folderState.value = DownloadFolderState.of(pathSaved = dir.toString().isNotBlank(), opened = true)
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
            // Said, not only logged: a folder saved that does not open kept every download inside
            // the app without a word (his phone, 11 Nov 2026). The Storage page reads this on the
            // folder's row, with the way to choose it again, and the move after a download says
            // it once (DownloadUtil).
            _folderState.value = DownloadFolderState.of(pathSaved = dir.toString().isNotBlank(), opened = false)
        }
    }

    /** The extra folders [extraDirs] opened, and the ones that would not open. */
    private fun openExtras(context: Context, extraDirs: List<Uri>): Pair<List<DocumentFile>, List<Uri>> {
        val opened = mutableListOf<DocumentFile>()
        val unreadable = mutableListOf<Uri>()
        for (extraUri in extraDirs) {
            val extraDf = runCatching { documentFileFromUri(context, extraUri) }.getOrNull()
            if (extraDf != null && extraDf.isDirectory) {
                opened.add(extraDf)
            } else {
                unreadable.add(extraUri)
            }
        }
        return opened to unreadable
    }

    /**
     * Deletes a song's file, from the main download folder only. The list holds the extra import
     * folders' files too, and those folders are promised never to be changed (the storage
     * tooltip), so a song imported from one keeps its file and has to be removed by hand.
     * Says what came of it ([FileRemoval]). The list is only made again by a scan, so it can hold
     * a file that a file manager deleted since: that one is gone already, and goes from the list
     * as a deleted one does, so that its song's download ends with it instead of staying listed.
     * Synchronized because deletes run in parallel on dlCoroutine, and the read-modify-write of
     * availableFiles below would otherwise lose one, leaving the player a deleted file.
     */
    @Synchronized
    fun deleteFile(mediaId: String): FileRemoval {
        val file = isExists(mediaId) ?: return FileRemoval.NOT_HERE
        if (!isInMainDir(file)) return FileRemoval.READ_ONLY
        val removal = fileRemoval(
            deleted = file.delete(),
            fileThere = { file.exists() },
            folderThere = { mainDir?.exists() == true },
        )
        // Out of the list as well, or the player went on handing out the deleted file for the
        // rest of the session and the song could not be streamed instead.
        if (removal.over) {
            availableFiles = availableFiles - file
            documentId(file)?.let { songFileIds.remove(it) }
        }
        return removal
    }

    fun saveFile(mediaId: String, input: InputStream, displayName: String?): Uri? {
        val directory = DocumentFile.fromTreeUri(context, dir)

        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val fileName = "$displayName [$mediaId].mka"
        // The song's own file under this very name already: kept, never a copy written beside it (ownFileAt).
        ownFileAt(mediaId, fileName)?.let { return it }
        // Whole or not at all (writeWhole): a half file under this name was taken for the download.
        val newFile = writeWhole(directory, fileName) { out -> input.copyTo(out) } ?: return null
        // In the list at once, as a song file is (saveSongFile): the player finds a download in the
        // folder through it, and Remove download deletes through it. Left out until the next full
        // scan, the song was streamed meanwhile and Remove download did nothing.
        addAvailable(TreeDocumentFileOt(null, context, newFile.uri, newFile.name ?: fileName, "audio/mka"))
        return newFile.uri
    }

    /**
     * Writes [fileName] into [directory] whole or not at all: under a name of its own while [write]
     * runs (PartialFiles), renamed to [fileName] once it is all there. The app ended halfway leaves
     * that part, which is never taken for a song and goes at a later full listing; a write that
     * fails deletes it. Null when the folder makes no file; a write or a rename that fails throws.
     * The file it gives back has the name the folder gave it ("Song (1).opus" next to another).
     * A folder whose files cannot be renamed, or that will not make the part, gets the final name
     * from the start, as before, and has only the delete on failure, as the automatic backup does
     * (AutoBackup.write): the move never fails where it used to work.
     */
    private fun writeWhole(directory: DocumentFile, fileName: String, write: (OutputStream) -> Unit): DocumentFile? {
        val partName = PartialFiles.nameWhileWritten(fileName)
        val made = directory.createFile(PartialFiles.MIME_TYPE, partName)
        val renames = made != null && canRename(made.uri)
        val file = if (renames) made!! else {
            made?.delete()
            directory.createFile(MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.')) ?: "audio/mka", fileName) ?: return null
        }
        val whole = try {
            // A stream that would not open used to return the new file's address all the same,
            // for an empty file.
            val out = context.contentResolver.openOutputStream(file.uri) ?: throw IOException("Could not open ${file.name}")
            out.use(write)
            if (!renames) file.uri else DocumentsContract.renameDocument(context.contentResolver, file.uri, fileName)
                ?: throw IOException("Could not name $partName $fileName")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return TreeDocumentFileOt(null, context, whole)
    }

    /** Whether the folder can rename [document]: the internal storage and cards can. */
    private fun canRename(document: Uri): Boolean = runCatching {
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { c -> c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0 }
    }.getOrNull() ?: false

    /** Under the same lock as [deleteFile], so a save and a delete at the same moment cannot undo each other in the list. */
    @Synchronized
    private fun addAvailable(file: DocumentFile) {
        availableFiles = availableFiles + file
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
        // The type Android itself gives the extension, for the list below: the file is made as a
        // part (writeWhole), whose type keeps its name as given, and takes the extension's type
        // once renamed.
        val extension = fileName.substringAfterLast('.')
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        // The song's own file under this very name already, "Artist - Title.opus" from an earlier
        // download the app no longer knew of: kept, never "Artist - Title (1).opus" beside it (ownFileAt).
        ownFileAt(mediaId, fileName)?.let { return it }
        val newFile = writeWhole(directory, fileName) { stream ->
            val buffered = BufferedOutputStream(stream, 64 * 1024)
            write(buffered)
            buffered.flush()
        } ?: return null
        documentId(newFile)?.let { songFileIds[it] = mediaId }
        val saved = TreeDocumentFileOt(null, context, newFile.uri, newFile.name ?: fileName, mimeType)
        addAvailable(saved)
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
        // A file being written, or one a move left half written: no song's, though its name may say one (PartialFiles).
        if (PartialFiles.isPartial(name)) return null
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

    /**
     * A download folder of the phone's own storage, whose files' document ids are the folder's own id,
     * "/" and their name ("primary:Music/InterTune/Song [id].mka"): [path] on the phone, [documentId]
     * the folder's. Null for a folder of another provider, whose ids say nothing of a path.
     */
    private class FolderRoot(val dir: DocumentFile, val path: String, val documentId: String)

    private fun rootOf(dir: DocumentFile): FolderRoot? = runCatching {
        if (dir.uri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching null
        val path = fileFromUri(context, dir.uri)?.absolutePath?.trimEnd('/') ?: return@runCatching null
        FolderRoot(dir, path, DocumentsContract.getDocumentId(dir.uri))
    }.getOrNull()

    /** [uri]'s name, type and size as its provider has them, or null when it gives no row (no such file, or no answer). */
    private fun document(uri: Uri): TreeDocumentFileOt? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
            val mime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE).takeIf { it >= 0 }?.let(c::getString)
            val size = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            // An empty file is no song: a write that never began.
            if (name == null || size == 0L) null else TreeDocumentFileOt(null, context, uri, name, mime)
        }
    }.getOrNull()

    /**
     * Looks at each song's own file, at the path the database has for it ([paths], by song), one by
     * one: for the songs a full listing did not bring. A listing can come back short, and a song
     * whose file was right there lost its download to it; the liked catch up then fetched it again
     * and the move put a second copy beside the first (dvd, 20 Nov 2026). A file found is in the list
     * from now on, as if listed, for the player, Remove download and the move.
     *
     * GONE: the folder answers and has no such file (FileRemoval's rule), or the path is under no
     * download folder of today's (the folder was changed since: a scan always let those go).
     * UNKNOWN: the folder does not answer either. Only the phone's own storage can be asked this way
     * (FolderRoot); a song under another provider's folder reads as UNKNOWN, and is kept.
     */
    fun lookAgain(paths: Map<String, String>): Map<String, FolderFileCheck> {
        if (paths.isEmpty()) return emptyMap()
        val roots = allDirs.mapNotNull { rootOf(it) }
        val answers = HashMap<String, Boolean>()
        val checked = paths.mapValues { (id, path) ->
            val root = roots.firstOrNull { path.startsWith(it.path + "/") }
                ?: return@mapValues if (allDirs.size == roots.size) FolderFileCheck.GONE else FolderFileCheck.UNKNOWN
            val uri = DocumentsContract.buildDocumentUriUsingTree(root.dir.uri, childDocumentId(root.documentId, path.removePrefix(root.path + "/")))
            val found = document(uri) ?: namedBack(id, root, path)
            if (found != null) {
                adopt(id, found)
                FolderFileCheck.THERE
            } else if (answers.getOrPut(root.path) { root.dir.exists() }) {
                FolderFileCheck.GONE
            } else {
                FolderFileCheck.UNKNOWN
            }
        }
        val there = checked.count { it.value == FolderFileCheck.THERE }
        val gone = checked.count { it.value == FolderFileCheck.GONE }
        Log.i(TAG, "Looked again at ${paths.size} songs the listing did not bring: $there there, $gone gone, ${paths.size - there - gone} not known")
        return checked
    }

    /** [file], found by a look of its own, is [mediaId]'s from now on, in the list as a listed one is. */
    private fun adopt(mediaId: String, file: TreeDocumentFileOt) {
        val name = file.name ?: return
        if (SongFileNames.isSongFile(name)) documentId(file)?.let { songFileIds[it] = mediaId }
        synchronized(this) {
            availableFiles = availableFiles.filterNot { it.uri == file.uri }.toSet() + file
        }
    }

    /**
     * [id]'s download under its own name, "<title> [<id>].mka", for a recorded file of the main folder
     * that is one of its copies and is not there: the clean-up names the copy a song keeps back to that
     * name, then records it (DownloadCopiesCleanup), and an app ended between the two left the database
     * the copy's old name. On a phone whose folder lists as empty the next start then let the song's
     * download go, its file right there (27 Nov 2026). Null for anything else: another folder, a folder
     * inside it, a name that is no copy of this song, nothing there.
     */
    private fun namedBack(id: String, root: FolderRoot, path: String): TreeDocumentFileOt? {
        if (root.dir !== mainDir) return null
        val relative = path.removePrefix(root.path + "/")
        if ('/' in relative) return null
        val own = DownloadCopies.ownNameOf(relative)?.takeIf { DownloadCopies.parse(relative)?.id == id } ?: return null
        val found = document(DocumentsContract.buildDocumentUriUsingTree(root.dir.uri, childDocumentId(root.documentId, own))) ?: return null
        Log.i(TAG, "$relative is not there, its song's download $own is: kept, as the clean-up named it")
        return found
    }

    /**
     * The song's own file already in a download folder, which a move or a download keeps rather than
     * write a second copy beside it: at the path the database has for it ([recordedPath]), or in the
     * main folder under the name a download of it has always had, "<title> [<id>].mka". Looked at
     * directly, so a listing that came back short does not hide it. Null when there is none to keep.
     * Each "(1)" in dvd's folder was a download written beside its own earlier file (20 Nov 2026).
     */
    fun fileAlreadyThere(mediaId: String, displayName: String?, recordedPath: String?): Uri? {
        if (recordedPath != null && lookAgain(mapOf(mediaId to recordedPath))[mediaId] == FolderFileCheck.THERE) {
            isExists(mediaId)?.let { return it.uri }
        }
        val main = mainDir?.let { rootOf(it) } ?: return null
        val name = fatName("$displayName [$mediaId].mka")
        val found = document(DocumentsContract.buildDocumentUriUsingTree(main.dir.uri, childDocumentId(main.documentId, name))) ?: return null
        Log.i(TAG, "$name is in the download folder already: kept, no second copy")
        adopt(mediaId, found)
        return found.uri
    }

    /** What the folder says of one file: its name, type, size (null when it does not say) and when it was last written (0 when not). */
    private class Described(val name: String, val mime: String?, val size: Long?, val modified: Long)

    /** [uri] as its provider has it, empty files included, or null when it gives no row (no such file, or no answer). */
    private fun described(uri: Uri): Described? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun column(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
            val name = column(DocumentsContract.Document.COLUMN_DISPLAY_NAME)?.let(c::getString) ?: return@use null
            Described(
                name = name,
                mime = column(DocumentsContract.Document.COLUMN_MIME_TYPE)?.let(c::getString),
                size = column(DocumentsContract.Document.COLUMN_SIZE)?.let(c::getLong),
                modified = column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let(c::getLong) ?: 0L,
            )
        }
    }.getOrNull()

    /**
     * [mediaId]'s own file already in the main folder under [fileName], the very name a write is about
     * to take: kept, and nothing written, since a second file under a name already taken comes out
     * numbered beside the first ("Artist - Title (1).opus", "Title [id] (1).mka"). The move looks for
     * the song's recorded file and its "<title> [<id>].mka" first (fileAlreadyThere), but in a folder
     * that lists as empty a song file "Artist - Title.opus" the app no longer knew of (data cleared, a
     * fresh install on the same folder) was missed there, and the move wrote the song again beside it.
     * Looked at by its name, as fileAlreadyThere does: only the phone's own storage can be asked so.
     *
     * Whose it is: the id in a download's name, or for a song file, whose name holds none, what is
     * known of it or its tags (SongFileIds). One of another song, or one whose tags say nothing, is not
     * taken: the write goes on and the folder numbers it, two songs of the same name. An empty file
     * under a download's name is no song but a write that never began: once it is no write's any more
     * ([emptyFileGoes]), it goes, and the write takes its name.
     */
    private fun ownFileAt(mediaId: String, fileName: String): Uri? {
        val main = mainDir?.let { rootOf(it) } ?: return null
        val name = fatName(fileName)
        val uri = DocumentsContract.buildDocumentUriUsingTree(main.dir.uri, childDocumentId(main.documentId, name))
        val found = described(uri) ?: return null
        if (found.size == 0L) {
            if (emptyFileGoes(SongFileNames.isSongFile(found.name), found.modified, System.currentTimeMillis())) {
                val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                Log.i(TAG, "${found.name} was empty, a write that never began: " + if (gone) "gone, the download takes its name" else "could not go")
            }
            return null
        }
        val file = TreeDocumentFileOt(null, context, uri, found.name, found.mime)
        val whose = if (SongFileNames.isSongFile(found.name)) songFileIdOf(file) else idOf(file)
        if (whose != mediaId) return null
        Log.i(TAG, "${found.name} is the song's own file already: kept, not written again beside it")
        adopt(mediaId, file)
        return uri
    }

    /** Which song the song file [file] is: what is known of it, or its tags, read once and remembered. */
    private fun songFileIdOf(file: DocumentFile): String? {
        val name = file.name ?: return null
        val docId = documentId(file)
        docId?.let { songFileIds[it] }?.let { return it }
        val read = runCatching {
            context.contentResolver.openInputStream(file.uri)?.use { SongFileIds.read(name, BufferedInputStream(it, 16 * 1024)) }
        }.getOrNull() ?: return SongFileNames.idInName(name)
        docId?.let { songFileIds[it] = read }
        return read
    }

    // ---- The extra copies of downloads a name clash made (DownloadCopies, DownloadCopiesCleanup)

    /** The main folder's own path on the phone, as the database has a file's, or null when it has none. */
    fun mainFolderPath(): String? = mainDir?.let { dir -> runCatching { fileFromUri(context, dir.uri)?.absolutePath?.trimEnd('/') }.getOrNull() }

    /**
     * The main folder's files that are ours (DownloadCopies.parse) and of a song with a copy
     * (DownloadCopies.ofSongsWithCopies), each with its size, from a listing of that folder alone: never
     * an extra folder, never a folder inside it. Null when it cannot be listed; nothing listed when it
     * lists as empty, as dvd's did, which [lookUp] gets round. Only those are looked at one by one: a
     * look is about a millisecond, and every download of everyone's folder had one at every start.
     */
    fun ourFilesListed(): DownloadCopies.Listed? {
        val listed = (mainDir as? TreeDocumentFileOt)?.listFilesOrNull() ?: return null
        val wanted = DownloadCopies.ofSongsWithCopies(listed.mapNotNull { it.name })
        val ours = listed.mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            if (name !in wanted) return@mapNotNull null
            described(file.uri)?.let { DownloadCopies.File(it.name, it.size, file.uri.toString()) }
        }
        return DownloadCopies.Listed(listed.size, ours)
    }

    /** Whether a file of the main folder can be looked at by its name ([lookUp]): on the phone's own storage and its cards. */
    fun canLookUp(): Boolean = mainDir?.let { rootOf(it) } != null

    /** The main folder's file called [name] as it is now, or null when there is none, or the folder cannot be asked. */
    fun lookUp(name: String): DownloadCopies.File? {
        val main = mainDir?.let { rootOf(it) } ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(main.dir.uri, childDocumentId(main.documentId, name))
        return described(uri)?.let { DownloadCopies.File(it.name, it.size, uri.toString()) }
    }

    /** The file at [key] as it is now, or null when it is gone, or the folder cannot be asked. */
    fun lookAt(key: String): DownloadCopies.File? = described(Uri.parse(key))?.let { DownloadCopies.File(it.name, it.size, key) }

    /** Deletes the extra copy at [key] and takes it out of the folder's list, under the lock of [deleteFile]. Whether it went. */
    @Synchronized
    fun removeCopy(key: String): Boolean {
        val uri = Uri.parse(key)
        val gone = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (gone) {
            availableFiles = availableFiles.filterNot { it.uri == uri }.toSet()
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.let { songFileIds.remove(it) }
        }
        return gone
    }

    /**
     * [mediaId]'s one file left at [key], renamed to [name] first when one is given: its address from
     * now on, in the folder's list as a listed file is. A rename that fails leaves it as it was, under
     * its old address.
     */
    fun keepFile(mediaId: String, key: String, name: String?): Uri {
        var uri = Uri.parse(key)
        if (name != null) {
            val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, name) }
                .onFailure { Log.w(TAG, "Could not name the file kept of $mediaId $name", it) }
                .getOrNull()
            if (renamed != null) {
                val old = uri
                synchronized(this) { availableFiles = availableFiles.filterNot { it.uri == old }.toSet() }
                uri = renamed
            }
        }
        described(uri)?.let { adopt(mediaId, TreeDocumentFileOt(null, context, uri, it.name, it.mime)) }
        return uri
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
            // Parts a move left half written when the app was ended: never a song, and gone from the
            // main folder once no move can be writing them. The extra folders are never changed.
            val now = System.currentTimeMillis()
            result.removeAll { file ->
                PartialFiles.isPartial(file.name).also { partial ->
                    if (partial && isInMainDir(file) && PartialFiles.leftBehind(file.lastModified(), now)) {
                        runCatching { file.delete() }.onSuccess { Log.i(TAG, "A part a move left behind goes: ${file.name}") }
                    }
                }
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
            // Said, so that a report shows a listing that came back short (dvd, 20 Nov 2026).
            Log.i(TAG, "Listed ${result.size} files in ${allDirs.size - unlistable.size} of ${allDirs.size} download folders")
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

/** The provider of the phone's own storage and its cards, the one whose document ids hold a path. */
const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * The id of the document [relative] under the folder [folderId], as the phone's own storage names
 * them: "primary:Music/InterTune" and "Song [id].mka" make "primary:Music/InterTune/Song [id].mka",
 * and a volume's top, "primary:", makes "primary:Song [id].mka".
 */
fun childDocumentId(folderId: String, relative: String): String =
    if (folderId.endsWith(":") || folderId.endsWith("/")) folderId + relative else "$folderId/$relative"

/**
 * [name] as the phone's own storage writes it (FileUtils.buildValidFatFilename): each character a
 * FAT card refuses becomes "_", so "What? [id].mka" is found as the "What_ [id].mka" it made.
 */
fun fatName(name: String): String = buildString(name.length) {
    for (c in name) append(if (c.code < 0x20 || c.code == 0x7F || c in "\"*/:<>?\\|") '_' else c)
}

/**
 * Whether an empty file in the way of a download's name goes before the download is written
 * (DownloadDirectoryManagerOt.ownFileAt): only one under a download's own name, "<title> [<id>].mka"
 * ([songFile] false: an empty song file cannot say whose it is), and only once it is no write's any
 * more (PartialFiles.leftBehind, [modified] against [now]): one being written now, by another app
 * too, is left alone, and the folder numbers the new file as before.
 */
fun emptyFileGoes(songFile: Boolean, modified: Long, now: Long): Boolean = !songFile && PartialFiles.leftBehind(modified, now)

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
 * What came of deleting a song's file from the main download folder (DownloadDirectoryManagerOt.deleteFile).
 * [over]: the song's download is over, whether the file went just now or was gone already; the
 * song leaves the Downloads list and its download state is cleared (DownloadUtil.deleteSong).
 */
enum class FileRemoval(val over: Boolean) {
    /** The file was deleted. */
    DELETED(true),

    /**
     * The folder's list held the file, but it is not there: a file manager deleted it since the
     * list was made. The folder answers and has no such file, so nothing is left to delete.
     */
    GONE(true),

    /** Nothing was tried: no file of the song in the list. */
    NOT_HERE(false),

    /**
     * Nothing was tried: the song's file is in an extra import folder, which is only read (the storage
     * tooltip). The person is told where it is: choosing Inside the app keeps the old download folder
     * as one, and Remove download there did nothing, without a word (26 Nov 2026).
     */
    READ_ONLY(false),

    /**
     * The file is there and could not be deleted (a permission, a provider that does not delete),
     * or the folder cannot be reached to say whether it is (a card not mounted, a grant that is
     * gone). The song stays listed, as a scan spares it while the folder's permission is held
     * (unlistedDownloadDirs, idsSparedByUnlistedFolders).
     */
    REFUSED(false),
}

/**
 * True at most once every [gapMs]: one note for a burst of removals that all meet the same thing (a
 * whole album's Remove download in an extra import folder), not a note per song.
 */
class OnceInAWhile(private val gapMs: Long) {
    private var last = Long.MIN_VALUE / 2

    @Synchronized
    fun due(now: Long): Boolean = (now - last >= gapMs).also { if (it) last = now }
}

/**
 * Tells a delete that went through, one that found the file gone already, and one that was refused.
 * A delete that did not go through is gone already only when the file is not there while the
 * folder answers: a folder that cannot be reached is not an empty one, and says nothing of the
 * file. Asked only as far as it has to be, each question being a call to the storage provider.
 */
fun fileRemoval(deleted: Boolean, fileThere: () -> Boolean, folderThere: () -> Boolean): FileRemoval = when {
    deleted -> FileRemoval.DELETED
    fileThere() -> FileRemoval.REFUSED
    folderThere() -> FileRemoval.GONE
    else -> FileRemoval.REFUSED
}

/**
 * Which download dirs a full listing has to treat as not looked at, rather than as empty.
 *
 * A main folder that is set but could not be opened means nothing was walked, the extra folders
 * included, since doInit gives up on all of them together, so every one of them counts. A blank
 * main folder means none of the person's own (the default, Reset, or Inside the app chosen), which
 * is not a folder that failed: counting it would make its unknown path cover every downloaded song,
 * and nothing downloaded could ever be cleared again. The extra folders are read without one all
 * the same, so theirs count as with one: the extras that could not be opened and the folders that
 * opened but could not be listed.
 */
fun unlistedDownloadDirs(
    main: String,
    mainOpened: Boolean,
    extras: List<String>,
    unopenedExtras: List<String>,
    unlistable: List<String>,
): List<String> = when {
    main.isBlank() -> unopenedExtras + unlistable
    !mainOpened -> listOf(main) + extras.filterNot { it == main }
    else -> unopenedExtras + unlistable
}
