package com.zionhuang.innertube

import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What YouTube says to one video's /player over each address family, from wherever this runs.
 *
 * Written for a line where the answer depends on the family: one French ISP's IPv6 got the bot
 * check from every client while IPv4 on the same line was served. Per family it asks the way the
 * app does, ANDROID_VR first (whose answer carries a new visitorData) and then VISIONOS with that
 * visitorData, and prints each playability status. A stream url carries the address it was issued
 * to, so every url that came back is then fetched with HEAD over both families, which shows whether
 * googlevideo minds a url being played over a family other than the one /player went over.
 *
 * Skipped unless IPFAMILY_PROBE=1, so an ordinary run and CI never touch the network. Eight requests
 * at most, two seconds apart, and no account data.
 *
 *     IPFAMILY_PROBE=1 ./gradlew :innertube:test --tests "*IpFamilyProbe*" -i
 *
 * IPFAMILY_VIDEO picks another video.
 */
class IpFamilyProbe {

    private val videoId = System.getenv("IPFAMILY_VIDEO")?.takeIf { it.isNotBlank() } ?: "fJ9rUzIMcZQ"

    private fun headClient(family: IpFamily) = OkHttpClient.Builder()
        .apply(keepToFamily(AddressPolicy(family, only = true)))
        .build()

    @Test
    fun probe() = runBlocking {
        assumeTrue("set IPFAMILY_PROBE=1 to run", System.getenv("IPFAMILY_PROBE") == "1")

        val urls = mutableMapOf<IpFamily, String>()
        for (family in IpFamily.entries) {
            val policy = AddressPolicy(family, only = true)
            val main = YouTube.player(videoId, client = ANDROID_VR_NO_AUTH, visitorData = null, hlOverride = "en", addressPolicy = policy)
            main.onFailure { println("${family.label} ANDROID_VR failed: $it") }
            val visitorData = main.getOrNull()?.let {
                println("${family.label} ANDROID_VR ${it.playabilityStatus.status} ${it.playabilityStatus.reason.orEmpty()}")
                it.responseContext.visitorData
            }
            if (main.isFailure) continue
            delay(2_000)

            val visionos = YouTube.player(videoId, client = VISIONOS, visitorData = visitorData, hlOverride = "en", addressPolicy = policy)
            visionos.onFailure { println("${family.label} VISIONOS failed: $it") }
            visionos.getOrNull()?.let { response ->
                val status = response.playabilityStatus
                val audio = response.streamingData?.adaptiveFormats?.filter { it.isAudio && it.url != null }.orEmpty()
                println("${family.label} VISIONOS ${status.status} ${status.reason.orEmpty()} (${audio.size} audio formats)")
                audio.firstOrNull()?.url?.let { urls[family] = it }
            }
            delay(2_000)
        }

        for ((issuedOver, url) in urls) {
            val ip = Regex("[?&]ip=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val carried = when {
                ip == null -> "no ip parameter"
                ':' in ip -> "an IPv6 ip parameter"
                else -> "an IPv4 ip parameter"
            }
            for (fetchedOver in IpFamily.entries) {
                val status = runCatching {
                    headClient(fetchedOver).newCall(Request.Builder().head().url(url).build()).execute().use { it.code }
                }.getOrElse { "failed (${it.javaClass.simpleName})" }
                println("url from ${issuedOver.label} /player, $carried, HEAD over ${fetchedOver.label}: $status")
                delay(2_000)
            }
        }
    }
}
