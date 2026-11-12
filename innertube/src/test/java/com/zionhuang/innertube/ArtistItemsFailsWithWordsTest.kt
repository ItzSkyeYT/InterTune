package com.zionhuang.innertube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An artist's list of songs or videos in a shape the app does not know. Offline: reads the source.
 *
 * #119 (10 Nov 2026): opening an artist's videos gave "Unknown error", and the report held three bare
 * NullPointerException lines and nothing else: `artistItems` forced the page's header and its shelf
 * with `!!`. A page with no header now keeps its songs, and a page with neither a grid nor a shelf
 * fails in words the next report carries.
 */
class ArtistItemsFailsWithWordsTest {

    private val body: String = File("src/main/java/com/zionhuang/innertube/YouTube.kt").readText()
        .substringAfter("suspend fun artistItems(endpoint: BrowseEndpoint)")
        .substringBefore("suspend fun artistItemsContinuation(")

    @Test
    fun `nothing in the page is forced`() {
        assertFalse("artistItems forces a field with !!", "!!" in body)
    }

    @Test
    fun `a page with no header keeps its songs, and an unknown shape says so`() {
        assertTrue(".text.orEmpty()" in body.substringAfter("else {"))
        assertTrue("the page has neither a grid nor a playlist shelf" in body)
    }
}
