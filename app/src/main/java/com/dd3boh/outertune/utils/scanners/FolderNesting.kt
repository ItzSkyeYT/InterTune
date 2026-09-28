/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.scanners

import java.net.URLDecoder

/**
 * Whether one picked folder lies inside another, for keeping download folders and scan folders
 * apart. No Android in here, so it is tested.
 *
 * A download folder inside a scan folder made every download a local song as well. The check that
 * was meant to stop it compared the two addresses as text and only caught the same folder twice,
 * and plain text would also take Music2 to be inside Music.
 */
object FolderNesting {

    /**
     * The folder a tree address from the folder picker points at, as its document id, such as
     * "primary:Music/Downloads", or null for an address that is not a tree.
     */
    fun treeDocumentId(uri: String): String? {
        val encoded = uri.substringAfter("/tree/", "").substringBefore('/')
        if (encoded.isEmpty()) return null
        return URLDecoder.decode(encoded, "UTF-8")
    }

    /** Whether the folder [child] is [parent] or somewhere under it. Both are tree addresses. */
    fun isSameOrInside(child: String, parent: String): Boolean {
        val c = treeDocumentId(child) ?: return child == parent
        val p = treeDocumentId(parent) ?: return false
        return isSameOrInsideId(c, p)
    }

    /**
     * Whether [a] and [b] are the same folder or one lies inside the other, either way round.
     * Both are tree addresses.
     */
    fun overlaps(a: String, b: String): Boolean = isSameOrInside(a, b) || isSameOrInside(b, a)

    /**
     * The same check as [isSameOrInside], taking the two folders' document ids directly (such as
     * "primary:Music/InterTune") instead of tree addresses. For comparing a found file's own
     * document id, which DocumentsContract.getDocumentId reads correctly from its full uri; a tree
     * address only carries the address it was picked at, never a file found under it.
     */
    fun isSameOrInsideId(childId: String, parentId: String): Boolean {
        if (childId == parentId) return true
        // The root of a volume is "primary:", and its folders are "primary:Music", with no slash.
        val prefix = if (parentId.endsWith(":")) parentId else parentId.trimEnd('/') + "/"
        return childId.startsWith(prefix)
    }
}
