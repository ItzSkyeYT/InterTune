package com.zionhuang.innertube

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.AlbumPage
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * What an album's two pages hold, for albums whose page would not open.
 *
 * YouTube.album reads the album page (MPREb_...) for the header, then its playlist page
 * (VL + OLAK5uy_...) for the songs, and albumSongs ended in !! on the playlist page's
 * musicPlaylistShelfRenderer. On 28 Sep "Five More Hours", opened from Stats, failed that way and
 * the album page never filled in. This prints, per album and locale, what each page carries in
 * place of that shelf and what YouTube.album now returns.
 *
 * Skipped unless ALBUM_PROBE=1 so an ordinary test run never touches the network. ALBUM_PROBE_IDS
 * (comma separated) replaces the album list; ALBUM_PROBE_DUMP names a folder to save the raw
 * responses in.
 *
 *     ALBUM_PROBE=1 ./gradlew :innertube:test --tests "*AlbumSongsProbe*" -i
 */
class AlbumSongsProbe {

    private val albums = System.getenv("ALBUM_PROBE_IDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf(
            // Both ids seen for "Five More Hours" on 28 Sep.
            "MPREb_hLZFwxCac4p",
            "MPREb_dDFLAnEcWVu",
            // The THE BOOK 2 YouTubeTest checks, meant as a control: its playlist page is empty too.
            "MPREb_oNAdr9eUOfS",
            // The THE BOOK 2 its page lists under other versions, whose playlist page is not.
            "MPREb_s27b9Eis9DC",
        )

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    private fun text(o: JsonElement?): String? = o.arr("runs")?.joinToString("") {
        ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
    } ?: ((o as? JsonObject)?.get("simpleText") as? JsonPrimitive)?.content

    /** One line per section: its renderer and, when it has rows, how many and of what kind. */
    private fun sections(label: String, list: JsonArray?) {
        if (list == null) {
            println("ALBUMPROBE     $label: none")
            return
        }
        println("ALBUMPROBE     $label: ${list.size} section(s)")
        list.forEachIndexed { i, s ->
            val o = s.jsonObject
            o.forEach { (kind, body) ->
                val rows = body.arr("contents")
                val rowKinds = rows?.map { it.jsonObject.keys.joinToString("+") }?.groupingBy { it }?.eachCount()
                val cont = body.arr("continuations")?.size ?: rows?.count { it.jsonObject.containsKey("continuationItemRenderer") }
                val title = text(body.obj("title")) ?: text(body.obj("header")?.values?.firstOrNull())
                println("ALBUMPROBE       [$i] $kind rows=${rows?.size} $rowKinds continuation=$cont title=$title")
            }
        }
    }

    /** The exception and where in the parsers it was thrown, since a !! says nothing by itself. */
    private fun trace(t: Throwable): String = "$t at " + t.stackTrace
        .filter { it.className.startsWith("com.zionhuang.innertube") && !it.className.contains("Probe") }
        .take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private fun describe(label: String, raw: String) {
        val root = Json.parseToJsonElement(raw).jsonObject
        println("ALBUMPROBE   $label top-level keys: ${root.keys}")
        root["alerts"]?.jsonArray?.forEach { a ->
            val body = a.jsonObject.values.firstOrNull()
            println("ALBUMPROBE     alert: ${text(body.obj("text"))}")
        }
        root["header"]?.jsonObject?.keys?.let { println("ALBUMPROBE     header: $it") }
        val contents = root["contents"]?.jsonObject
        if (contents == null) {
            println("ALBUMPROBE     no contents")
            return
        }
        println("ALBUMPROBE     contents: ${contents.keys}")
        contents.forEach { (kind, body) ->
            body.arr("tabs")?.forEachIndexed { t, tab ->
                sections("$kind tab $t", tab.obj("tabRenderer").obj("content").obj("sectionListRenderer").arr("contents"))
            }
            sections("$kind secondaryContents", body.obj("secondaryContents").obj("sectionListRenderer").arr("contents"))
            sections("$kind sectionList", body.obj("sectionListRenderer").arr("contents"))
        }
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("ALBUM_PROBE") == "1")
        val dump = System.getenv("ALBUM_PROBE_DUMP")?.let(::File)?.also { it.mkdirs() }

        for ((gl, hl) in listOf("US" to "en", "FR" to "fr")) {
            val locale = YouTubeLocale(gl = gl, hl = hl)
            YouTube.locale = locale
            val inner = InnerTube()
            inner.locale = locale
            // The app always sends a visitorData, so the probe does too.
            if (YouTube.visitorData == null) YouTube.visitorData = YouTube.visitorData().getOrNull()
            inner.visitorData = YouTube.visitorData
            println("")
            println("ALBUMPROBE ######## locale $hl-$gl ########")

            for (id in albums) {
                println("ALBUMPROBE album $id")
                val albumRaw = inner.browse(WEB_REMIX, id).bodyAsText()
                dump?.let { File(it, "$id-$hl.json").writeText(albumRaw) }
                describe("album page", albumRaw)
                val canonical = (Json.parseToJsonElement(albumRaw).obj("microformat").obj("microformatDataRenderer")
                    ?.get("urlCanonical") as? JsonPrimitive)?.content
                val playlistId = canonical?.substringAfterLast('=')
                println("ALBUMPROBE   urlCanonical=$canonical")
                if (playlistId != null) {
                    val vlRaw = runCatching { inner.browse(WEB_REMIX, "VL$playlistId").bodyAsText() }
                    vlRaw.onFailure { println("ALBUMPROBE   VL$playlistId request failed: $it") }
                    vlRaw.onSuccess { raw ->
                        dump?.let { File(it, "VL$playlistId-$hl.json").writeText(raw) }
                        describe("VL$playlistId", raw)
                    }
                    // get_queue, the other way to ask for a playlist's songs, and albumSongs as its
                    // own callers (album radio, an OLAK5uy_ link) see it.
                    YouTube.queue(playlistId = playlistId).fold(
                        onSuccess = { q -> println("ALBUMPROBE   get_queue -> ${q.size} songs, album=${q.firstOrNull()?.album?.id}") },
                        onFailure = { println("ALBUMPROBE   get_queue FAILED: $it") },
                    )
                    YouTube.albumSongs(playlistId).fold(
                        onSuccess = { s -> println("ALBUMPROBE   YouTube.albumSongs -> ${s.size} songs") },
                        onFailure = { println("ALBUMPROBE   YouTube.albumSongs FAILED: $it") },
                    )
                }
                YouTube.album(id).fold(
                    onSuccess = { page ->
                        println("ALBUMPROBE   YouTube.album -> '${page.album.title}' by ${page.album.artists?.map { it.name }} " +
                            "songs=${page.songs.size} otherVersions=${page.otherVersions.size}")
                        page.songs.take(3).forEach { s ->
                            println("ALBUMPROBE     ${s.id} '${s.title}' ${s.artists.map { it.name }} ${s.duration}s album=${s.album?.id}")
                        }
                    },
                    onFailure = { println("ALBUMPROBE   YouTube.album FAILED: ${trace(it)}") },
                )
            }
        }
    }

    /**
     * How common an empty playlist page is, over albums found by searching: per album, what the
     * playlist page lists, what the album's own page lists (and how many of those rows are the
     * music video rather than the audio track), and what YouTube.album returns.
     *
     *     ALBUM_SURVEY=1 ./gradlew :innertube:test --tests "*AlbumSongsProbe.survey*"
     */
    @Test
    fun survey() = runBlocking {
        assumeTrue(System.getenv("ALBUM_SURVEY") == "1")
        val queries = System.getenv("ALBUM_SURVEY_QUERIES")?.split(',')?.map { it.trim() }
            ?: listOf(
                "Five More Hours Deorro", "THE BOOK 2 YOASOBI", "Random Access Memories", "Abbey Road",
                "After Hours The Weeknd", "Rumours Fleetwood Mac", "Thriller Michael Jackson",
                "Civilisation Orelsan", "Racine carrée Stromae", "Bach Goldberg Variations Glenn Gould",
                "NOW That's What I Call Music 100", "Midnights Taylor Swift", "Discovery Daft Punk",
                "Wind Resistance Southbound", "Un Verano Sin Ti", "Hybrid Theory",
            )
        val locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.locale = locale
        if (YouTube.visitorData == null) YouTube.visitorData = YouTube.visitorData().getOrNull()
        val inner = InnerTube().also { it.locale = locale; it.visitorData = YouTube.visitorData }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        var total = 0
        var emptyPlaylist = 0
        var failed = 0

        for (q in queries) {
            val albums = YouTube.search(q, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()?.items
                ?.filterIsInstance<AlbumItem>()?.take(2).orEmpty()
            for (album in albums) {
                total++
                val raw = inner.browse(WEB_REMIX, album.browseId).bodyAsText()
                val response = json.decodeFromString(BrowseResponse.serializer(), raw)
                val own = runCatching {
                    AlbumPage.getSongs(response, YouTube.albumPage(album.browseId, response, null, false).album)
                }.getOrNull()
                val ownTypes = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                    ?.contents?.firstNotNullOfOrNull { it.musicShelfRenderer }?.contents?.getItems()
                    ?.map {
                        it.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs
                            ?.watchEndpointMusicConfig?.musicVideoType?.removePrefix("MUSIC_VIDEO_TYPE_")
                    }?.groupingBy { it }?.eachCount()
                val listed = YouTube.albumSongs(album.playlistId)
                if (listed.getOrNull().isNullOrEmpty()) emptyPlaylist++
                val result = YouTube.album(album.browseId)
                if (result.isFailure) failed++
                val source = when {
                    result.isFailure -> "FAILED ${trace(result.exceptionOrNull()!!)}"
                    !listed.getOrNull().isNullOrEmpty() -> "${result.getOrNull()!!.songs.size} songs from the playlist page"
                    result.getOrNull()!!.songs.isNotEmpty() -> "${result.getOrNull()!!.songs.size} songs from its own page"
                    else -> "header only, no songs"
                }
                println("SURVEY ${album.browseId} '${album.title}' by ${album.artists?.joinToString { it.name }}")
                println("SURVEY   playlist page: ${listed.fold({ "${it.size} songs" }, { "none ($it)" })}; " +
                    "own page: ${own?.size} songs $ownTypes; album: $source")
            }
        }
        println("SURVEY total=$total emptyPlaylistPage=$emptyPlaylist albumFailed=$failed")
    }
}
