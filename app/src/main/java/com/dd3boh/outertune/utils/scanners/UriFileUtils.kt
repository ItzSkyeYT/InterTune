package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
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

        if (uri.authority != "com.android.externalstorage.documents") return null

        val docId = DocumentsContract.getDocumentId(uri)
        val (type, relativePath) = volumeAndPath(docId) ?: return null

        val rootDir = when (type.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> {
                // Try to handle secondary storage
                val secondaryStorage = "/storage/$type"
                if (File(secondaryStorage).exists()) {
                    File(secondaryStorage)
                } else {
                    null
                }
            }
        }

        return rootDir?.let { File(it, relativePath) }
    }
}

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
