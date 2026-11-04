package com.zionhuang.innertube.models.body

import com.zionhuang.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class EditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<Action>
)

@Serializable
sealed class Action {
    /**
     * [dedupeOption] DEDUPE_OPTION_SKIP has YouTube add the song even when the playlist holds it
     * already, as ytmusicapi's add_playlist_items(duplicates=True) sends it; left out (null, which
     * is not encoded), YouTube refuses a song the playlist holds.
     */
    @Serializable
    data class AddVideoAction(
        val action: String = "ACTION_ADD_VIDEO",
        val addedVideoId: String,
        val dedupeOption: String? = null,
    ) : Action()

    @Serializable
    data class AddPlaylistAction(
        val action: String = "ACTION_ADD_PLAYLIST",
        val addedFullListId: String
    ) : Action()

    @Serializable
    data class MoveVideoAction(
        val action: String = "ACTION_MOVE_VIDEO_BEFORE",
        val setVideoId: String,
        val movedSetVideoIdSuccessor: String
    ) : Action()

    @Serializable
    data class RemoveVideoAction(
        val action: String = "ACTION_REMOVE_VIDEO",
        val setVideoId: String,
        val removedVideoId: String
    ) : Action()

    @Serializable
    data class RenamePlaylistAction(
        val action: String = "ACTION_SET_PLAYLIST_NAME",
        val playlistName: String
    ) : Action()
}
