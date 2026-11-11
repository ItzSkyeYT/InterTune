/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.ui.player.QueueUndo.Taken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Undo for what was taken out of the queue by hand (11 Nov 2026): the songs go back where they were,
 * in order, the song playing and its place untouched. The places are plain numbers, so the rule is
 * held on lists; the same steps are then run on the queue's own bookkeeping (MultiQueueObject), the
 * very methods the sheet calls, shuffled and not.
 */
class QueueUndoTest {

    private val queue = File("src/main/java/com/dd3boh/outertune/ui/player/Queue.kt").readText()

    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 180, genre = null)

    /** [list] with the songs at [places] taken out, the last place first as the sheet takes them. */
    private fun <T> takeOut(list: List<T>, places: List<Int>): Pair<List<T>, List<Taken<T>>> {
        val rest = list.toMutableList()
        val taken = ArrayList<Taken<T>>()
        for (place in places.sortedDescending()) taken += Taken(place, rest.removeAt(place))
        return rest to taken
    }

    private val ten = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")

    @Test
    fun `one song goes back to the place it had`() {
        val (rest, taken) = takeOut(ten, listOf(4))
        assertEquals(listOf("a", "b", "c", "d", "f", "g", "h", "i", "j"), rest)
        assertEquals(ten, QueueUndo.putBack(rest, taken))
    }

    @Test
    fun `several go back each to its own place, in order, whichever order they are given in`() {
        val (rest, taken) = takeOut(ten, listOf(1, 4, 5, 9))
        // As the sheet takes them, the last place first, and in the other order too.
        assertEquals(listOf(9, 5, 4, 1), taken.map { it.place })
        assertEquals(ten, QueueUndo.putBack(rest, taken))
        assertEquals(ten, QueueUndo.putBack(rest, taken.reversed()))
    }

    @Test
    fun `the first, the last, and a run of neighbours go back as they stood`() {
        for (places in listOf(listOf(0), listOf(9), listOf(0, 9), listOf(3, 4, 5), listOf(0, 1, 2, 3, 4, 5, 6, 7, 8))) {
            val (rest, taken) = takeOut(ten, places)
            assertEquals(places.toString(), ten, QueueUndo.putBack(rest, taken))
        }
    }

    @Test
    fun `the same song twice goes back to both its places`() {
        val twice = listOf("x", "y", "x", "z", "x")
        val (rest, taken) = takeOut(twice, listOf(0, 2))
        assertEquals(listOf("y", "z", "x"), rest)
        assertEquals(twice, QueueUndo.putBack(rest, taken))
    }

    @Test
    fun `a queue that grew meanwhile gets them back at their places, what came after left behind them`() {
        val (rest, taken) = takeOut(ten, listOf(2, 7))
        val grown = rest + listOf("k", "l")
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"), QueueUndo.putBack(grown, taken))
    }

    @Test
    fun `a queue that shrank meanwhile takes a place past its end at its end, in the order they were taken`() {
        val (rest, taken) = takeOut(ten, listOf(2, 7, 9))
        assertEquals(listOf("a", "b", "d", "e", "f", "g", "i"), rest)
        // Four songs more went from its end meanwhile: three are left, fewer than the places 7 and 9.
        val shrunk = rest.dropLast(4)
        assertEquals(listOf("a", "b", "d"), shrunk)
        assertEquals(listOf(2, 4, 5), QueueUndo.insertions(taken, shrunk.size).map { it.place })
        assertEquals(listOf("a", "b", "c", "d", "h", "j"), QueueUndo.putBack(shrunk, taken))
    }

    @Test
    fun `into an empty queue they go in, in order`() {
        val (_, taken) = takeOut(ten, listOf(5, 1, 3))
        assertEquals(listOf("b", "d", "f"), QueueUndo.putBack(emptyList(), taken))
    }

    @Test
    fun `nothing taken, nothing put back`() {
        assertEquals(ten, QueueUndo.putBack(ten, emptyList()))
        assertTrue(QueueUndo.insertions(emptyList<Taken<String>>(), 5).isEmpty())
    }

    @Test
    fun `the places to put at are counted as the queue grows by each one`() {
        val (rest, taken) = takeOut(ten, listOf(1, 4, 5, 9))
        assertEquals(listOf(1, 4, 5, 9), QueueUndo.insertions(taken, rest.size).map { it.place })
        // A queue left with two songs: each place is held to the size the queue has by then.
        assertEquals(listOf(1, 3, 4, 5), QueueUndo.insertions(taken, 2).map { it.place })
    }

    // The queue's own bookkeeping, as the sheet runs it: out from the last place down, in from the first up.

    private fun playOrder(q: MultiQueueObject) = q.getCurrentQueueShuffled().map { it.id }

    @Test
    fun `an unshuffled queue is as it was, and the song playing is still the one`() {
        val q = MultiQueueObject(
            id = 1, title = "Q", queue = MutableList(10) { i -> song("s$i").apply { shuffleIndex = i } }, queuePos = 6, index = 0
        )
        val before = playOrder(q)
        val taken = ArrayList<Taken<MediaMetadata>>()
        for (place in listOf(8, 5, 2, 1)) {
            taken += Taken(place, q.getCurrentQueueShuffled()[place])
            assertTrue(q.removeAtPlayIndex(place, currentPlayIndex = q.getQueuePosShuffled()))
        }
        assertEquals("s6", q.getCurrentSong()?.id)
        assertEquals(3, q.getQueuePosShuffled())
        for (at in QueueUndo.insertions(taken, q.getSize())) q.insertAtPlayIndex(at.place, listOf(at.song))
        assertEquals(before, playOrder(q))
        assertEquals("the song playing is still the one", "s6", q.getCurrentSong()?.id)
        assertEquals("and at its place", 6, q.getQueuePosShuffled())
    }

    @Test
    fun `a shuffled queue is as it was, and the song playing is still the one`() {
        // Ten songs s0 to s9 stored in order, played in the order 3 7 0 9 1 5 2 8 4 6, playing the fifth (s1).
        val order = listOf(3, 7, 0, 9, 1, 5, 2, 8, 4, 6)
        val songs = MutableList(10) { i -> song("s$i").apply { shuffleIndex = order.indexOf(i) } }
        val q = MultiQueueObject(id = 2, title = "S", queue = songs, shuffled = true, queuePos = 1, index = 0)
        val before = playOrder(q)
        assertEquals(4, q.getQueuePosShuffled())
        val taken = ArrayList<Taken<MediaMetadata>>()
        for (place in listOf(9, 6, 2, 0)) {
            taken += Taken(place, q.getCurrentQueueShuffled()[place])
            assertTrue(q.removeAtPlayIndex(place, currentPlayIndex = q.getQueuePosShuffled()))
        }
        assertEquals("s1", q.getCurrentSong()?.id)
        for (at in QueueUndo.insertions(taken, q.getSize())) q.insertAtPlayIndex(at.place, listOf(at.song))
        assertEquals(before, playOrder(q))
        assertEquals("s1", q.getCurrentSong()?.id)
        assertEquals(4, q.getQueuePosShuffled())
        assertTrue("shuffle stays on", q.shuffled)
    }

    // What the sheet does with it, in the source: every way of taking songs out offers Undo.

    @Test
    fun `the swipe, the pill's button and the menu's entry all offer Undo with what they took`() {
        val swipe = queue.substringAfter("val removeSwiped: () -> Boolean = swipe@{").substringBefore("val onCheckedChange")
        assertTrue("QueueUndo.Taken(swipedIndex, " in swipe && "offerUndo(" in swipe)
        val picked = queue.substringAfter("val removeSelected").substringBefore("\n    }\n")
        assertTrue("QueueUndo.Taken(place, song)" in picked && "offerUndo(" in picked)
        // The pill and the menu are one function, the only one given to them.
        assertEquals(1, Regex("""onRemoveFromQueue = removeSelected,""").findAll(queue).count())
    }

    @Test
    fun `the message is the title of one song or the count of several, in the app's own snackbar with Undo`() {
        val offer = queue.substringAfter("val offerUndo").substringBefore("// Undo ends here")
        assertTrue("R.string.queue_removed_one" in offer && "R.plurals.queue_removed_songs" in offer)
        assertTrue("actionLabel = undoLabel" in offer && "SnackbarDuration.Long" in offer)
        assertTrue("snackbarHostState.showSnackbar(" in offer)
        // A second removal while the bar shows replaces it: only the last can be undone.
        assertTrue("snackbarHostState.currentSnackbarData?.dismiss()" in offer)
        assertTrue(offer.indexOf("currentSnackbarData?.dismiss()") < offer.indexOf("showSnackbar("))
        assertTrue("only an Undo that was tapped puts anything back", "SnackbarResult.ActionPerformed" in offer)
        // Not for a queue that went with its last songs.
        assertTrue("getSize() > 0" in offer)
    }

    @Test
    fun `Undo goes into the queue it came out of, the board first and then the player, or the saved queue alone before play`() {
        val back = queue.substringAfter("val putBackTaken").substringBefore("val offerUndo")
        assertTrue("only the queue they came out of", ".id == queueId" in back)
        assertTrue("QueueUndo.insertions(taken, " in back)
        val board = back.indexOf("qb.putBack(")
        val player = back.indexOf("player.addMediaItem(")
        assertTrue("the board first", board in 0 until player)
        assertTrue("val loaded = playerConnection.player.mediaItemCount > 0" in back)
        assertTrue("if (loaded) playerConnection.player.addMediaItem(" in back)
        assertTrue("if (!loaded) playerConnection.storedQueueEdited()" in back)
        // The play of the song and its place are never asked for: nothing seeks, plays or reloads.
        for (never in listOf("seekTo", "setCurrQueue", "play()", "prepare()", "playWhenReady")) assertFalse(never, never in back)
    }

    @Test
    fun `the words are held from translators in English and there in French, with plurals`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue("""<string name="queue_removed_one" translatable="false">Removed %1${'$'}s</string>""" in en)
        assertTrue("""<plurals name="queue_removed_songs" translatable="false">""" in en)
        assertTrue("""<string name="queue_removed_one">""" in fr && """<plurals name="queue_removed_songs">""" in fr)
        val plural = fr.substringAfter("""<plurals name="queue_removed_songs">""").substringBefore("</plurals>")
        for (quantity in listOf("one", "many", "other")) assertTrue(quantity, """quantity="$quantity"""" in plural)
        // Undo is the app's own word, not a new one.
        assertTrue(File("src/main/res/values/strings.xml").readText().contains("""<string name="undo">Undo</string>"""))
    }
}
