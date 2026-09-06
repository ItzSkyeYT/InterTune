package com.zionhuang.innertube

import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live request, signed out. YouTube added a Comments tab to the watch-next response, which moved
 * Related from position 2 to 3 and silently emptied every related-songs lookup. This pins the
 * lookup to the tab's browse id so the next reshuffle fails loudly here instead.
 */
class NextTabsTest {
    @Test
    fun nextExposesLyricsAndRelatedTabs() = runBlocking {
        val next = YouTube.next(WatchEndpoint(videoId = "CiSzE1YAONw")).getOrThrow()
        assertTrue("watch-next returned no items", next.items.isNotEmpty())
        assertNotNull("lyrics tab not found", next.lyricsEndpoint)
        assertNotNull("related tab not found", next.relatedEndpoint)
        assertTrue(next.lyricsEndpoint!!.browseId.startsWith("MPLY"))
        assertTrue(next.relatedEndpoint!!.browseId.startsWith("MPTR"))

        val related = YouTube.related(next.relatedEndpoint!!).getOrThrow()
        assertTrue("related page has no songs", related.songs.isNotEmpty())
    }
}
