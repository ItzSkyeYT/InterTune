package com.zionhuang.innertube

import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.body.NextBody
import com.zionhuang.innertube.pages.WatchPlaylistPage
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a radio asks when YouTube Music's own answer is refused, and how YouTube's own mix is read.
 *
 * No request leaves: the requests are built and read as ImportSearchTest reads a search, and the
 * answers are two that YouTube gave from Tor exits on 4 Nov 2026 (probe/answers in
 * experiments/features/radio-fallback), cut down to a few rows, with a live stream and an
 * unplayable row added to the first.
 */
class RadioFallbackRequestsTest {

    /** A client signed in, as App sets it up: a visitor id, an account id and the cookie. */
    private fun signedIn() = InnerTube().apply {
        locale = YouTubeLocale(gl = "CO", hl = "es-CO")
        visitorData = "visitor-kept"
        dataSyncId = "channel-1"
        cookie = "SID=one; SAPISID=two; __Secure-3PAPISID=three"
    }

    private val json = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    private fun HttpRequestBuilder.sent(): JsonObject = json.encodeToJsonElement(NextBody.serializer(), body as NextBody).jsonObject

    private fun JsonObject.at(vararg path: String): String? {
        var o: JsonObject = this
        for (key in path.dropLast(1)) o = o[key]?.jsonObject ?: return null
        return o[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `asked as a visitor, the request carries no cookie and names no account`() {
        val client = signedIn()
        val request = HttpRequestBuilder().apply {
            with(client) {
                visitorNextRequest(YouTubeClient.WEB_REMIX, "AlNEtjBX1wU", null, null, null, null, null, visitorData = "visitor-fresh")
            }
        }
        assertNull(request.headers["cookie"])
        assertNull(request.headers["Authorization"])
        val body = request.sent()
        assertNull("no account named", body.at("context", "user", "onBehalfOfUser"))
        assertEquals("the visitor's id is the one given", "visitor-fresh", body.at("context", "client", "visitorData"))
        assertEquals("WEB_REMIX", body.at("context", "client", "clientName"))
        assertEquals("AlNEtjBX1wU", body.at("videoId"))
        // As YouTube Music's page sends it, otherwise.
        assertEquals("67", request.headers["X-YouTube-Client-Name"])
        assertEquals(YouTubeClient.ORIGIN_YOUTUBE_MUSIC, request.headers["X-Origin"])
    }

    @Test
    fun `YouTube's own mix is asked as a visitor of YouTube, not of YouTube Music`() {
        val client = signedIn()
        val request = HttpRequestBuilder().apply {
            with(client) { watchNextRequest(YouTubeClient.WEB, "AlNEtjBX1wU", "RDAlNEtjBX1wU", "OAE%3D", visitorData = "visitor-fresh") }
        }
        assertNull(request.headers["cookie"])
        assertNull(request.headers["Authorization"])
        assertNull("nothing of YouTube Music's", request.headers["X-Origin"])
        assertEquals("1", request.headers["X-YouTube-Client-Name"])
        assertEquals("visitor-fresh", request.headers["X-Goog-Visitor-Id"])
        val body = request.sent()
        assertNull(body.at("context", "user", "onBehalfOfUser"))
        assertEquals("RDAlNEtjBX1wU", body.at("playlistId"))
        assertEquals("OAE%3D", body.at("params"))
        assertEquals("CO", body.at("context", "client", "gl"))
    }

    private fun answer(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/radio/$name")!!.readText()).jsonObject

    @Test
    fun `WEB's mix is read as a radio, the song first, the channel for its artist`() {
        val result = WatchPlaylistPage.parse(answer("web-mix.json"), WatchEndpoint(videoId = "AlNEtjBX1wU", playlistId = "RDAlNEtjBX1wU"))!!
        assertEquals("Mix - Bye, Summer (바이, 썸머)", result.title)
        // The live stream and the unplayable row are left out.
        assertEquals(listOf("AlNEtjBX1wU", "IHIt9S2DWcQ", "5_IYVichXbA", "tRB5OCnlPR4"), result.items.map { it.id })
        assertEquals(0, result.currentIndex)
        val first = result.items.first()
        assertEquals("Bye, Summer (바이, 썸머)", first.title)
        assertEquals(219, first.duration)
        assertEquals("이지금 [IU Official]", first.artists.single().name)
        assertEquals("UC3SyT4_WLHzN7JmHQwKQZww", first.artists.single().id)
        assertTrue(first.thumbnail.startsWith("https://i.ytimg.com/vi/AlNEtjBX1wU/"))
        // Nothing is said of what YouTube does not say.
        assertFalse(first.explicit)
        assertNull(first.album)
        assertNull(first.musicVideoType)
        assertNull("a mix has no next page of its own", result.continuation)
    }

    @Test
    fun `ANDROID_VR's mix, with runs where WEB has simpleText, reads the same way`() {
        val result = WatchPlaylistPage.parse(answer("android-vr-mix.json"), WatchEndpoint(videoId = "AlNEtjBX1wU", playlistId = "RDAMVMAlNEtjBX1wU"))!!
        assertEquals(4, result.items.size)
        assertEquals("AlNEtjBX1wU", result.items.first().id)
        assertEquals("My sea (아이와 나의 바다)", result.items[1].title)
        assertEquals(317, result.items[1].duration)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `an answer with no mix in it is none, and a channel of YouTube's own is named for its artist`() {
        assertNull(WatchPlaylistPage.parse(Json.parseToJsonElement("""{"contents":{"twoColumnWatchNextResults":{"results":{}}}}""").jsonObject, WatchEndpoint(videoId = "x")))
        assertNull(WatchPlaylistPage.parse(Json.parseToJsonElement("{}").jsonObject, WatchEndpoint(videoId = "x")))
        assertEquals("IU", WatchPlaylistPage.channelName("IU - Topic"))
        assertEquals("TAEYEON Official", WatchPlaylistPage.channelName("TAEYEON Official"))
    }
}
