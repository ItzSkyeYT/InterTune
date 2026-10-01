package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicPlaylistShelfRenderer(
    val playlistId: String?,
    // A playlist that is empty on YouTube comes with a shelf and no contents at all, which failed
    // the whole read with "Field 'contents' is required".
    val contents: List<MusicShelfRenderer.Content> = emptyList(),
    val collapsedItemCount: Int,
)
