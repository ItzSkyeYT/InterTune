/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.listenbrainz.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One listen as /1/submit-listens takes it: the request's "payload" is a list of these.
 *
 * Made by ListenBrainz.listen, kept as JSON by whatever holds listens for later
 * (ListenBrainz.encode), and sent as it was first made. ListenBrainz keeps a listen sent twice
 * once (its store drops a second row with the same time, user and recording), so a listen that
 * may or may not have arrived can simply be sent again.
 *
 * Absent values are left out of the JSON rather than sent as null, as ListenBrainz asks: "If you
 * do not have the data for any of the following fields, omit the key entirely."
 */
@Serializable
data class Listen(
    /**
     * When playback STARTED, in seconds since the epoch ("playback start time of the submitted
     * track"). Required for a finished or saved listen, and must be absent from "playing now".
     */
    @SerialName("listened_at") val listenedAt: Long? = null,
    @SerialName("track_metadata") val trackMetadata: TrackMetadata,
)

@Serializable
data class TrackMetadata(
    @SerialName("artist_name") val artistName: String,
    @SerialName("track_name") val trackName: String,
    /** The album. ListenBrainz drops an empty one, so it is left out instead. */
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("additional_info") val additionalInfo: AdditionalInfo? = null,
)

/**
 * The part of additional_info this app fills. ListenBrainz's list is longer ("not exhaustive");
 * MBIDs, tags and the rest are not sent because the app has none worth sending for a YouTube song.
 */
@Serializable
data class AdditionalInfo(
    /** The song's length. Also how long ListenBrainz shows a "playing now" before clearing it. */
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** A string in ListenBrainz's table of fields, the first track of a release being "1". */
    @SerialName("tracknumber") val trackNumber: String? = null,
    @SerialName("media_player") val mediaPlayer: String? = null,
    @SerialName("media_player_version") val mediaPlayerVersion: String? = null,
    @SerialName("submission_client") val submissionClient: String? = null,
    @SerialName("submission_client_version") val submissionClientVersion: String? = null,
    /** A domain, never a name or a link: "music.youtube.com" for YouTube Music, in ListenBrainz's own table. */
    @SerialName("music_service") val musicService: String? = null,
    /** Where the song can be heard: its page, "do not resolve the URL to an audio resource". */
    @SerialName("origin_url") val originUrl: String? = null,
)

/** What ListenBrainz.validateToken learnt. A token ListenBrainz does not know is valid = false, not a failure. */
data class TokenCheck(
    val valid: Boolean,
    /** The account's name on ListenBrainz, when the token is valid. */
    val userName: String?,
)

/**
 * /1/validate-token's answer. It is HTTP 200 either way, the verdict in [valid]:
 * {"code":200,"message":"Token invalid.","valid":false} for a made-up token (asked 18 Nov 2026).
 */
@Serializable
internal data class TokenResponse(
    val code: Int? = null,
    val message: String? = null,
    val valid: Boolean? = null,
    @SerialName("user_name") val userName: String? = null,
)

/** Every refusal's body: {"code":401,"error":"Invalid authorization token."} (asked 18 Nov 2026). */
@Serializable
internal data class ErrorResponse(
    val code: Int? = null,
    val error: String? = null,
)
