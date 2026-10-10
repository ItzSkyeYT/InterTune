package com.zionhuang.innertube.models.body

import com.zionhuang.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class PlayerBody(
    val context: Context,
    val videoId: String,
    val playlistId: String?,
    val playbackContext: PlaybackContext? = null,
    val serviceIntegrityDimensions: ServiceIntegrityDimensions? = null,
    val contentCheckOk: Boolean = true,
    val racyCheckOk: Boolean = true,
    /** Sent by YouTube's own pages beside the two above. Null leaves it out, as every request of the app's did. */
    val videoCheckOk: Boolean? = null,
) {
    @Serializable
    data class PlaybackContext(
        val contentPlaybackContext: ContentPlaybackContext,
        val adPlaybackContext: AdPlaybackContext? = null,
    ) {
        @Serializable
        data class ContentPlaybackContext(
            val signatureTimestamp: Int,
            /** "HTML5_PREF_WANTS" from YouTube's own pages. Null leaves it out. */
            val html5Preference: String? = null,
        )

        /**
         * Asks for the answer of a play with no ad before the song. A web client's stream address
         * is otherwise refused for as long as that ad would have run (yt-dlp's
         * use_ad_playback_context, which its notes say is not to be sent for a Premium account:
         * the answer then lacks the Premium formats).
         */
        @Serializable
        data class AdPlaybackContext(
            val pyv: Boolean = true,
        )
    }

    @Serializable
    data class ServiceIntegrityDimensions(
        val poToken: String
    )
}
