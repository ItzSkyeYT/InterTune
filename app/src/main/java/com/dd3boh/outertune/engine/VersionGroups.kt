/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.utils.SongVersions

/**
 * Which songs are one song to a listener: equal non-empty base titles, or a version link from
 * YouTube, transitively. The artist is ignored on purpose, so a cover by someone else joins the
 * original; the cost of a false merge is one card for one build, the cost of a false split is two
 * versions side by side, which is the failure the maintainer named.
 *
 * The artist is still handed to the base title, but only so that an "Artist - Track" upload is
 * read as the track, and so groups exactly as its plain-titled release would. On the 11 Sep
 * library that moved 112 titles: 40 joined their own artist's release, 18 joined other artists'
 * songs of the same name, which is the accepted cost above, and two wrong groups came apart, one
 * of them four different Pizza Hotline songs that had all been reduced to "pizza hotline".
 */
class VersionGroups(songs: Collection<SongRow>, links: Collection<VersionLink> = emptyList()) {
    private val parent = HashMap<String, String>()

    private fun find(x: String): String {
        var root = x
        while (true) { val p = parent[root] ?: break; if (p == root) break; root = p }
        var cur = x
        while (true) { val p = parent[cur] ?: break; if (p == root) break; parent[cur] = root; cur = p }
        return root
    }

    private fun union(a: String, b: String) {
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    init {
        val byTitle = HashMap<String, String>()
        for (s in songs) {
            parent.putIfAbsent(s.id, s.id)
            val base = SongVersions.baseTitle(s.title, s.artistName)
            if (base.isEmpty()) continue
            val first = byTitle.putIfAbsent(base, s.id)
            if (first != null) union(s.id, first)
        }
        for (l in links) {
            parent.putIfAbsent(l.songId, l.songId); parent.putIfAbsent(l.versionId, l.versionId)
            union(l.songId, l.versionId)
        }
    }

    /** The group's id: any member's id, stable for the life of this object. */
    fun groupOf(songId: String): String = if (parent.containsKey(songId)) find(songId) else songId

    fun sameGroup(a: String, b: String): Boolean = groupOf(a) == groupOf(b)
}
