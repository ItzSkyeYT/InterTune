package com.zionhuang.innertube.models.response

import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * PlayerResponse with [com.zionhuang.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
data class PlayerResponse(
    val responseContext: ResponseContext,
    val playabilityStatus: PlayabilityStatus,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
    @SerialName("playbackTracking")
    val playbackTracking: PlaybackTracking?,
    /**
     * The ads the answer schedules, as YouTube sent them. Kept as they came: only the ones before
     * the song are read, by the app's StartAds, and only for a web client's answer.
     */
    val adPlacements: List<JsonObject>? = null,
    val adSlots: List<JsonObject>? = null,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String?,
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig,
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double?,
            val perceptualLoudnessDb: Double?,
            val loudnessTargetLkfs: Double? = null,
        ) {
            /**
             * Loudness above YouTube's normalisation target, in dB, which is what the player needs
             * to attenuate by. Positive means the track is louder than target.
             *
             * YouTube stopped sending `loudnessDb` and now sends `perceptualLoudnessDb` measured
             * against `loudnessTargetLkfs` instead, so reading only the old field silently yields
             * null and normalisation quietly does nothing.
             *
             * The two are related by `loudnessDb = perceptualLoudnessDb - loudnessTargetLkfs`.
             * Verified against real data rather than inferred: for wAoq__SQpwk the API now returns
             * perceptualLoudnessDb -7.73 with target -14, and the value stored back when the old
             * field still existed was 6.27; for yauDRYS_bnk, -7.95 and -14 against a stored 6.05.
             * Both match exactly.
             *
             * Target defaults to -14 LKFS, which is what YouTube has used throughout and what both
             * probes returned, so a response that omits it still normalises correctly.
             */
            val effectiveLoudnessDb: Double?
                get() = loudnessDb
                    ?: perceptualLoudnessDb?.minus(loudnessTargetLkfs ?: DEFAULT_LOUDNESS_TARGET_LKFS)

            companion object {
                const val DEFAULT_LOUDNESS_TARGET_LKFS = -14.0
            }
        }
    }

    @Serializable
    data class StreamingData(
        val formats: List<Format>?,
        val adaptiveFormats: List<Format>,
        val expiresInSeconds: Int,
    ) {
        @Serializable
        data class Format(
            val itag: Int,
            val url: String?,
            val mimeType: String,
            val bitrate: Int,
            val width: Int?,
            val height: Int?,
            val contentLength: Long?,
            val quality: String,
            val fps: Int?,
            val qualityLabel: String?,
            val averageBitrate: Int?,
            val audioQuality: String?,
            val approxDurationMs: String?,
            val audioSampleRate: Int?,
            val audioChannels: Int?,
            val loudnessDb: Double?,
            val lastModified: Long?,
            val signatureCipher: String?,
            /**
             * What the stream's sound is, as a small protobuf map in base64url. For a video offered
             * in several languages its "acont" is "original", "dubbed" or "dubbed-auto" and its
             * "lang" the language; a stable volume stream has "drc" at "1". Read by the app's
             * OriginalAudio. Absent from a song's streams.
             */
            val xtags: String? = null,
            /** The language of the stream, for a video offered in several (#151). Null for a video in one. */
            val audioTrack: AudioTrack? = null,
        ) {
            val isAudio: Boolean
                get() = width == null

            /**
             * One of the languages a video's sound is offered in: the original, a dub its channel
             * uploaded, or one YouTube made by machine. Every stream of that language carries it.
             * [id] is the language and a number ("en-US.4", "it.10"), [displayName] its name in the
             * language the answer was asked in, with "original" after it for the original, and
             * [audioIsDefault] whether YouTube's own player would start in it.
             */
            @Serializable
            data class AudioTrack(
                val id: String? = null,
                val displayName: String? = null,
                val audioIsDefault: Boolean? = null,
            )
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String,
        val title: String,
        val author: String,
        val channelId: String,
        val lengthSeconds: String,
        val musicVideoType: String?,
        val viewCount: String,
        val thumbnail: Thumbnails,
    )

    @Serializable
    data class PlaybackTracking(
        @SerialName("videostatsPlaybackUrl")
        val videostatsPlaybackUrl: VideostatsPlaybackUrl?,
        @SerialName("videostatsWatchtimeUrl")
        val videostatsWatchtimeUrl: VideostatsWatchtimeUrl?,
        @SerialName("atrUrl")
        val atrUrl: AtrUrl?,
    ) {
        @Serializable
        data class VideostatsPlaybackUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )

        @Serializable
        data class VideostatsWatchtimeUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
        @Serializable
        data class AtrUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
    }
}