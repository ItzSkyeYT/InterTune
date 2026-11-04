package com.zionhuang.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * YouTube Music's answer to browse/edit_playlist. An add answers with the id the new entry has in
 * the playlist (its setVideoId), which is what a later removal or move of that entry has to name:
 * ytmusicapi reads it from the same place (playlistEditResults, playlistEditVideoAddedResultData).
 * Every field may be missing; nothing here is required for the edit to count.
 */
@Serializable
data class EditPlaylistResponse(
    val status: String? = null,
    val playlistEditResults: List<PlaylistEditResult>? = null,
) {
    @Serializable
    data class PlaylistEditResult(
        val playlistEditVideoAddedResultData: VideoAdded? = null,
    )

    @Serializable
    data class VideoAdded(
        val videoId: String? = null,
        val setVideoId: String? = null,
    )

    /** YouTube answered, and said no: STATUS_FAILED. A duplicate it was not told to keep is one. */
    val refused: Boolean get() = status?.contains("FAILED", ignoreCase = true) == true

    /** The entry an add of [videoId] made, by its setVideoId, or null when the answer does not say. */
    fun setVideoIdOf(videoId: String): String? = playlistEditResults.orEmpty()
        .mapNotNull { it.playlistEditVideoAddedResultData }
        .firstOrNull { it.videoId == null || it.videoId == videoId }
        ?.setVideoId
}
