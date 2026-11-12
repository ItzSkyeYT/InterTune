/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.BlockedArtistsKey
import com.dd3boh.outertune.models.ArtistBlock
import com.dd3boh.outertune.models.BlockedArtist
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Blocks and unblocks an artist (ArtistBlock), in the one setting that holds them. Written away
 * from the caller, which is often a menu closing at that moment.
 */
object ArtistBlocking {
    private const val TAG = "ArtistBlocking"

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Blocks [artist] now, and then, for one of YouTube's with no picture yet (a song's credits
     * carry none), looks its picture up for the list of blocked artists.
     */
    fun block(context: Context, artist: BlockedArtist) {
        val app = context.applicationContext
        val entry = artist.copy(at = if (artist.at > 0) artist.at else System.currentTimeMillis())
        writes.launch {
            edit(app) { ArtistBlock.add(it, entry) }
            val id = entry.id
            if (entry.thumbnail == null && id != null && ArtistBlock.isYouTubeId(id)) {
                val picture = YouTube.artist(id).getOrNull()?.artist?.thumbnail ?: return@launch
                edit(app) { list -> list.map { if (it.id == id && it.thumbnail == null) it.copy(thumbnail = picture) else it } }
            }
        }
    }

    fun unblock(context: Context, artist: BlockedArtist) {
        val app = context.applicationContext
        writes.launch { edit(app) { ArtistBlock.remove(it, artist) } }
    }

    private suspend fun edit(context: Context, change: (List<BlockedArtist>) -> List<BlockedArtist>) {
        runCatching {
            context.dataStore.edit { settings ->
                settings[BlockedArtistsKey] = ArtistBlock.encode(change(ArtistBlock.decode(settings[BlockedArtistsKey])))
            }
        }.onFailure { Log.w(TAG, "Could not write the blocked artists", it) }
    }
}
