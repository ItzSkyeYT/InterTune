package com.dd3boh.lastfm

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class SessionResponse(val session: Session)

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
