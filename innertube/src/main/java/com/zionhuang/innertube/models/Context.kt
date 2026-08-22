package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    private val request: Request = Request(),
    private val user: User = User(),
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val osVersion: String?,
        val gl: String,
        val hl: String,
        val visitorData: String?,
        /**
         * Device identity fields. Only the Apple-device clients (VISIONOS) need these; every
         * other client leaves them null and they are dropped from the payload because the
         * Json config sets explicitNulls = false.
         */
        val osName: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String,
    )

    @Serializable
    data class Request(
        val internalExperimentFlags: Array<String> = emptyArray(),
        val useSsl: Boolean = true,
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false,
        val onBehalfOfUser: String? = null,
    )
}
