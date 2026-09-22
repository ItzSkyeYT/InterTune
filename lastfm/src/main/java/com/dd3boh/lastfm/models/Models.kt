package com.dd3boh.lastfm

import kotlinx.serialization.Serializable

/**
 * Both of these carry either the payload or an error, never both, because Last.fm reports failure
 * with HTTP 200 and an error body. Everything is therefore nullable and the caller checks.
 */
@Serializable
data class TokenResponse(
    val token: String? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class SessionResponse(
    val session: Session? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class Session(
    val name: String,
    val key: String,
    val subscriber: Int = 0,
)

/**
 * Last.fm answers a failed write with HTTP 200 and an error code in the body, so every response has
 * to be read even when the request "succeeded".
 */
@Serializable
data class ApiResult(
    val error: Int? = null,
    val message: String? = null,
)

/**
 * track.getSimilar. A public read, so it needs the API key and no session: similarity works for
 * somebody who has never connected an account, which is the whole reason it is worth having.
 *
 * Last.fm wraps the list in an object and, when it knows the track but has nothing like it,
 * answers with the wrapper and no array at all rather than an empty one. So [similartracks] and
 * its [SimilarTracks.track] are both nullable and both mean the same thing to a caller: nothing.
 */
@Serializable
data class SimilarResponse(
    val similartracks: SimilarTracks? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class SimilarTracks(
    val track: List<SimilarTrack>? = null,
)

@Serializable
data class SimilarTrack(
    val name: String,
    val artist: SimilarArtist? = null,
    /** 0 to 1, Last.fm's own confidence. Carried so a caller can rank or cut on it. */
    val match: Double? = null,
)

@Serializable
data class SimilarArtist(
    val name: String? = null,
)
