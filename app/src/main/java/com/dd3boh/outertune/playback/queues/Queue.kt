package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?
    val playlistId: String?
    val startShuffled: Boolean
    suspend fun getInitialStatus(): Status
    fun hasNextPage(): Boolean
    suspend fun nextPage(): List<MediaMetadata>

    data class Status(
        val title: String?,
        val items: List<MediaMetadata>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        /**
         * The list with [preload], the song already playing from its one-song queue, in the
         * place YouTube says playback starts, and that place. It used to go first whatever the
         * index said, so an endpoint carrying a playlist and an index (a Home shelf song can)
         * lost the list's real first song and held the tapped one twice.
         */
        fun withPreload(preload: MediaMetadata?): Pair<List<MediaMetadata>, Int> {
            if (items.isEmpty()) return items to 0
            val start = mediaItemIndex.coerceIn(0, items.lastIndex)
            if (preload == null) return items to start
            return items.toMutableList().also { it[start] = preload } to start
        }
    }
}
