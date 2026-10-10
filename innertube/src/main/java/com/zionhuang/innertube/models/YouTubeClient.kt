package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osVersion: String? = null,
    val osName: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val useWebPoTokens: Boolean = false,
    val isEmbedded: Boolean = false,
    // val origin: String? = null,
    // val referer: String? = null,
) {
    fun toContext(
        locale: YouTubeLocale,
        visitorData: String?,
        dataSyncId: String?,
        hlOverride: String? = null,
    ) = Context(
        client = Context.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            osVersion = osVersion,
            gl = locale.gl,
            hl = hlOverride ?: locale.hl,
            visitorData = visitorData,
            osName = osName,
            deviceMake = deviceMake,
            deviceModel = deviceModel
        ),
        user = Context.User(
            onBehalfOfUser = if (loginSupported) dataSyncId else null
        ),
    )

    companion object {
        /**
         * Should be the latest Firefox ESR version.
         */
        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        const val USER_AGENT_VISIONOS = "com.google.ios.youtube/1.02 (RealityDevice17,1; U; visionOS 2_3)"

        /**
         * Apple Vision Pro. Exempt from the GVS proof-of-origin requirement that YouTube began
         * enforcing on ANDROID_VR and IOS in August 2026, so its urls serve a whole track instead
         * of stopping after the ~1MB cold-start allowance (which is what produced
         * "Source error (2004): Response code: 403" partway through every song).
         *
         * Needs no signature deobfuscation. Requires a visitorData to clear the bot check, which
         * toContext already supplies.
         *
         * Verified 2026-08-22: full 6.13MB track downloaded, versus IOS stopping at 1.05MB.
         */
        val VISIONOS = YouTubeClient(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = USER_AGENT_VISIONOS,
            osVersion = "2.3.21O5565d",
            osName = "visionOS",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            loginSupported = false,
            useSignatureTimestamp = false,
        )

        /** The desktop Safari that yt-dlp's visionos client says it is. */
        const val USER_AGENT_SAFARI_26 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        /**
         * [VISIONOS] as two other projects say it, for the day YouTube stops taking it the way this
         * app says it. The client is the same and what it says of itself differs: the app's version
         * and user agent in the first, as NewPipeExtractor has them (ClientsConstants.java), and the
         * browser's user agent in the second, as yt-dlp has it (_base.py), both read on 9 Oct 2026.
         * Each played a whole song that morning, asked by the stream probe.
         *
         * What YouTube closes when it closes a client has so far been one way of saying it: a
         * version it no longer takes, a user agent it has learned to distrust. So these are asked
         * right after [VISIONOS] and cost a request each, where the web client costs a WebView.
         */
        val VISIONOS_1_04 = VISIONOS.copy(
            clientVersion = "1.04",
            userAgent = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)",
            osVersion = "26.6.0.23O770",
        )
        val VISIONOS_SAFARI = VISIONOS.copy(userAgent = USER_AGENT_SAFARI_26, osVersion = "26.5.23O471")

        val WEB = YouTubeClient(
            clientName = "WEB",
            clientVersion = "2.20250312.04.00",
            clientId = "1",
            userAgent = USER_AGENT_WEB,
        )

        val WEB_REMIX = YouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20250310.01.00",
            clientId = "67",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )

        val WEB_CREATOR = YouTubeClient(
            clientName = "WEB_CREATOR",
            clientVersion = "1.20250312.03.01",
            clientId = "62",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true,
        )

        val TVHTML5 = YouTubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20250312.16.00",
            clientId = "7",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true
        )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = YouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true,
            isEmbedded = true,
        )

        val IOS = YouTubeClient(
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osVersion = "18.3.2.22D82",
        )

        val ANDROID = YouTubeClient(
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            loginSupported = true,
            useSignatureTimestamp = true
        )

        val ANDROID_VR_NO_AUTH = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            loginSupported = false,
            useSignatureTimestamp = false
        )
    }
}
