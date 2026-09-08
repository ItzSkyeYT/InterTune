package com.zionhuang.innertube

import com.zionhuang.innertube.pages.HomePage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live request, signed out. Pins the shape the home refresh depends on.
 *
 * Refreshing used to reprint the same recommendations in a new order, and the measurement is why:
 * the first FEmusic_home response is 3 sections and about 25 items, two of them taken back to back
 * share roughly half their items, and the rest of the feed only arrives through continuations.
 * Loading one response is therefore not loading the home feed, it is loading a third of it.
 *
 * HomeViewModel now reads past the first response on every load, so this fails if continuations
 * ever stop carrying items the first response did not, which would quietly return the home screen
 * to the small repetitive pool it came from.
 */
class HomeRefreshTest {

    /**
     * The app pins a visitorData for the whole session, and continuation tokens are issued against
     * one. Without it every request opens a fresh anonymous session, a token from the previous one
     * means nothing, and the continuation comes back as an empty page. That is a property of the
     * test rather than of the app, and it is worth stating because it looks exactly like a broken
     * parser.
     */
    private suspend fun withVisitorData() {
        if (YouTube.visitorData == null) {
            YouTube.visitorData = YouTube.visitorData().getOrThrow()
        }
    }

    private fun HomePage.itemIds(): List<String> = sections.flatMap { section ->
        section.items.map { it.id }
    }

    @Test
    fun continuationsCarryItemsTheFirstResponseDidNot() = runBlocking {
        withVisitorData()

        val first = YouTube.home().getOrThrow()
        assertTrue("home returned no items", first.itemIds().isNotEmpty())
        assertTrue("home offered no continuation", first.continuation != null)

        val seen = first.itemIds().toMutableSet()
        var page = first
        var batches = 0
        var freshTotal = 0

        while (batches < 3) {
            val cont = page.continuation ?: break
            page = YouTube.home(cont).getOrThrow()
            val ids = page.itemIds()
            val fresh = ids.toSet() - seen
            seen += ids
            freshTotal += fresh.size
            batches++
            println("batch $batches: ${ids.size} items, ${fresh.size} new, ${seen.size} unique so far")
        }

        println("first response: ${first.itemIds().size} items; walked $batches continuations; ${seen.size} unique")
        assertTrue("no continuation was read", batches > 0)
        assertTrue("continuations added nothing the first response did not have", freshTotal > 0)
    }
}
