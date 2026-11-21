package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import java.io.File

fun documentFileFromUri(context: Context, uris: List<Uri>): List<DocumentFile> {
    return uris.map { customDocFileFromTreeTreeUri(context, it) }.filter { it.isDirectory }
}

fun documentFileFromUri(context: Context, uri: Uri): DocumentFile? {
    return customDocFileFromTreeTreeUri(context, uri)
}

fun stringFromUriList(uris: List<Uri>): String {
    if (uris.isEmpty()) return ""
    return uris.distinctBy { it.toString() }.joinToString("\n")
}

fun uriListFromString(str: String): List<Uri> {
    return str.split("\n").map { it.toUri() }.filter { it.toString().isNotBlank() }.distinctBy { it.toString() }
}

fun fileFromUri(context: Context, uri: Uri): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!DocumentsContract.isTreeUri(uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null

        val treeDocId = DocumentsContract.getDocumentId(uri)
        val rootId: String
        val relativePath: String

        if (treeDocId.contains(":")) {
            val parts = treeDocId.split(":", limit = 2)
            rootId = parts[0]
            relativePath = parts[1]
        } else {
            rootId = treeDocId
            relativePath = ""
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        val rootDir = if (rootId.equals("primary", ignoreCase = true)) {
            storageManager.primaryStorageVolume.directory
        } else {
            storageManager.storageVolumes.firstOrNull {
                it.uuid != null && it.uuid.equals(rootId, ignoreCase = true)
            }?.directory
        }

        return rootDir?.let { if (relativePath.isEmpty()) it else File(it, relativePath) }
    } else {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null

        @Suppress("DEPRECATION")
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
        val path = documentPathBelowR(
            authority = uri.authority,
            docId = DocumentsContract.getDocumentId(uri),
            primaryDir = Environment.getExternalStorageDirectory().path,
            documentsDir = documents,
            cardDir = { volume -> "/storage/$volume".takeIf { File(it).exists() } },
        ) ?: return null
        return File(path)
    }
}

/** The storage provider: the phone's storage and its cards, and below Android 11 its Documents root ("home"). */
internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** The Downloads provider. Below Android 11 a scan folder can be picked in Downloads. */
internal const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

/**
 * The path of a document below Android 11, from its provider and id, or null when it has none a file can be opened by
 * (#243, 21 Nov 2026). Android 9's folder picker offers Downloads and Documents before the phone's storage, and only
 * the storage's own ids were read here, so every song of a folder picked in either was "Could not access file":
 * - the phone's storage and its cards: "primary:Music/a.mp3" under [primaryDir], "1234-ABCD:Music/a.mp3" under
 *   [cardDir] of "1234-ABCD" (null when that card is not in);
 * - Documents, the storage provider's "home" root: "home:Songs/a.mp3" under [documentsDir], the phone's Documents
 *   folder (ExternalStorageProvider 9.0.0_r1, ROOT_ID_HOME);
 * - Downloads: "raw:/storage/emulated/0/Download/a.mp3", the file's own path after "raw:" (DownloadStorageProvider
 *   9.0.0_r1, RawDocumentsHelper). A file the download list knows by its number ("1234") has no path to read here.
 */
internal fun documentPathBelowR(
    authority: String?,
    docId: String,
    primaryDir: String,
    documentsDir: String,
    cardDir: (String) -> String?,
): String? = when (authority) {
    EXTERNAL_STORAGE_AUTHORITY -> volumeAndPath(docId)?.let { (volume, path) ->
        val root = when (volume.lowercase()) {
            "primary" -> primaryDir
            "home" -> documentsDir
            else -> cardDir(volume)
        }
        root?.let { if (path.isEmpty()) it else "${it.trimEnd('/')}/$path" }
    }
    DOWNLOADS_AUTHORITY -> docId.takeIf { it.startsWith("raw:/") }?.removePrefix("raw:")
    else -> null
}

/**
 * The picker for scan folders. Below Android 11 it shows the phone's storage from the start (EXTRA_SHOW_ADVANCED, read
 * by DocumentsUI 9.0.0_r1's Shared.mustShowDeviceRoot): Android 9's picker otherwise keeps it behind ⋮ > Show
 * internal storage and offers Downloads and Documents first (#243).
 */
class ScanFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input).apply {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) putExtra(EXTRA_SHOW_ADVANCED, true)
    }
}

/** DocumentsContract.EXTRA_SHOW_ADVANCED, hidden in the SDK. */
private const val EXTRA_SHOW_ADVANCED = "android.content.extra.SHOW_ADVANCED"

/**
 * The volume and the path inside it of a storage document id: "primary" and "Music/Live: Paris.mp3" for
 * "primary:Music/Live: Paris.mp3". Split at the first ':' only, as a file's or a folder's name can hold one: split at
 * every one, a removed file's line pointed at another path before Android 11 (LocalRemoval). Null without a ':'.
 */
internal fun volumeAndPath(docId: String): Pair<String, String>? =
    docId.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }

fun absoluteFilePathFromUri(context: Context, uri: Uri): String? {
    val dfUri = documentFileFromUri(context, uri)?.uri
    if (dfUri == null) return null
    return fileFromUri(context, dfUri)?.absolutePath
}

/** A scan folder by its own name, "Music" for /storage/emulated/0/Music, for the line under the row. */
fun folderName(context: Context, uri: Uri): String =
    absoluteFilePathFromUri(context, uri)?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: uri.toString()

private fun customDocFileFromTreeTreeUri(context: Context, uri: Uri) = TreeDocumentFileOt(
    null, context, DocumentsContract.buildDocumentUriUsingTree(
        uri, DocumentsContract.getTreeDocumentId(uri)
    )
)
