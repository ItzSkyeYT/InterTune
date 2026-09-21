package com.zionhuang.innertube

import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Why a Shazam match does not become something playable.
 *
 * RecognitionEngine turns a recognition into a YouTube search of "title artist" under FILTER_SONG,
 * once, with no fallback, so one empty result ends the pipeline and the person is told the app is
 * unsure. A real run produced exactly that: Shazam named the track and the search found nothing.
 *
 * This runs the same query the app runs, then progressively looser ones, and prints what each
 * returns. The point is to find out which rung of the ladder recovers a track the first rung
 * misses, and at what cost in wrong answers, before changing the engine.
 *
 * Skipped unless RESOLVE_PROBE=1 so an ordinary test run and CI never touch the network.
 *
 *     RESOLVE_PROBE=1 ./gradlew :innertube:test --tests "*ResolutionProbe*" -i
 *
 * Cases default to the set below. Override with RESOLVE_CASES as "title|artist" joined by ";;",
 * which is how to ask about a track a real listen has just failed on:
 *
 *     RESOLVE_CASES="Wind Resistance (Southbound Edit)|Actual Artist" RESOLVE_PROBE=1 ./gradlew ...
 */
class ResolutionProbe {

    private data class Case(val title: String, val artist: String)

    /**
     * Blinding Lights is the control: LiveRecognitionProbe matched it off a real speaker, so if the
     * first rung fails here the network or the parser is broken rather than the query.
     *
     * Wind Resistance is the recognition that actually failed. Its artist was never written down,
     * so it doubles as the empty-artist case, which is worth seeing on its own: corresponds()
     * refuses outright when the artist is empty, so even a perfect hit could not be added.
     *
     * Children is from Correspondence.kt, where a real test lost two minutes to YouTube listing it
     * as "Children (Dream Version)". A parenthetical the reference recording does not carry is the
     * whole failure class.
     */
    private val defaults = listOf(
        Case("Blinding Lights", "The Weeknd"),
        Case("Wind Resistance (Southbound Edit)", ""),
        Case("Children", "Robert Miles"),
    )

    private fun cases(): List<Case> {
        val raw = System.getenv("RESOLVE_CASES")
        if (raw.isNullOrBlank()) return defaults
        return raw.split(";;").mapNotNull {
            val parts = it.split("|")
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Case(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
        }
    }

    /** What a fallback would strip: bracketed asides the reference recording does not carry. */
    private fun bare(title: String): String = title
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun ladder(case: Case): List<Triple<String, String, YouTube.SearchFilter>> {
        val full = listOfNotNull(case.title, case.artist.ifBlank { null }).joinToString(" ")
        val stripped = listOfNotNull(bare(case.title), case.artist.ifBlank { null }).joinToString(" ")
        val rungs = mutableListOf(
            Triple("1 title+artist SONG  (what the app does)", full, FILTER_SONG),
        )
        if (stripped != full) rungs += Triple("2 bare+artist  SONG", stripped, FILTER_SONG)
        rungs += Triple("3 title+artist VIDEO", full, FILTER_VIDEO)
        if (stripped != full) rungs += Triple("4 bare+artist  VIDEO", stripped, FILTER_VIDEO)
        rungs += Triple("5 title only   SONG", case.title, FILTER_SONG)
        if (bare(case.title) != case.title) {
            rungs += Triple("6 bare only    SONG", bare(case.title), FILTER_SONG)
        }
        return rungs
    }

    /**
     * Where the search is run from, which is not a detail.
     *
     * The probe runs from a desktop with no account; the app runs on a phone in France with one.
     * YouTube Music catalogue availability is regional, so a track that answers in US/en can be
     * absent in FR/fr, and that difference alone would explain a search that found nothing on the
     * device and everything here. Override with RESOLVE_LOCALES as "gl:hl" joined by commas.
     */
    private fun locales(): List<Pair<String, String>> {
        val raw = System.getenv("RESOLVE_LOCALES")
        if (raw.isNullOrBlank()) return listOf("US" to "en", "FR" to "fr")
        return raw.split(",").mapNotNull {
            val parts = it.trim().split(":")
            if (parts.size < 2) null else parts[0] to parts[1]
        }
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue(System.getenv("RESOLVE_PROBE") == "1")

        for ((gl, hl) in locales()) {
        YouTube.locale = YouTubeLocale(gl = gl, hl = hl)
        println("")
        println("RESOLVE ######## locale $hl-$gl ########")
        for (case in cases()) {
            println("")
            println("RESOLVE === '${case.title}' by '${case.artist}' ===")
            for ((label, query, filter) in ladder(case)) {
                val result = YouTube.search(query, filter)
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    println("RESOLVE   $label -> THREW ${failure::class.simpleName}: ${failure.message}")
                    continue
                }
                val all = result.getOrNull()?.items.orEmpty()
                val songs = all.filterIsInstance<SongItem>()
                if (songs.isEmpty()) {
                    println("RESOLVE   $label -> 0 songs (${all.size} items of any kind)  q=\"$query\"")
                    continue
                }
                println("RESOLVE   $label -> ${songs.size} songs  q=\"$query\"")
                songs.take(3).forEach {
                    val artists = it.artists.joinToString(", ") { a -> a.name }
                    println("RESOLVE       '${it.title}' by '$artists' [${it.id}] ${it.duration}s")
                }
            }
        }
        }
    }
}
