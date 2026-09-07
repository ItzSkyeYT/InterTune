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
