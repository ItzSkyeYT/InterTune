/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His words of 9 Nov 2026, over a screenshot of his widget mid-resize: "Why can't the widget refresh
 * as quick as possible?" Glance composes a home screen widget on the main thread, and what it
 * drew there for a new cover (a crate, a row of records, the blurred cover, the glass) took the
 * better part of a second; a resize left the old layout stretched until the new one was drawn. So the
 * pictures are drawn on workers before the composition asks for them, and nothing is stretched. This
 * reads the source, because the order of a write, a drawing and a launcher cannot be seen without a phone.
 */
class WidgetDrawAheadTest {
    private val dir = "src/main/java/com/dd3boh/outertune/widget"
    private val store = File("$dir/WidgetStore.kt").readText()
    private val widget = File("$dir/MusicWidget.kt").readText()
    private val receiver = File("$dir/MusicWidgetReceiver.kt").readText()
    private val pictures = File("$dir/WidgetPictures.kt").readText()
    private val glass = File("$dir/WidgetGlass.kt").readText()

    @Test
    fun `a write draws its pictures on workers before the widget is told about it`() {
        val write = store.substringAfter("private suspend fun write(").substringBefore("/** One widget's settings")
        val ahead = write.indexOf("warm(context, drawn)")
        val told = write.indexOf("_drawn.value = drawn")
        assertTrue(write, ahead in 0 until told)
    }

    @Test
    fun `the widget is composed ahead in memory, never through a preview that saves a layout file`() {
        val warm = store.substringAfter("private suspend fun warm(").substringBefore("suspend fun warmResized(")
        assertTrue("composeAhead(" in warm)
        assertFalse("renderWidget(" in warm)
        // The in-memory composer: nothing is saved, no widget id is made up (renderWidget's makes a layout file each).
        val ahead = widget.substringAfter("internal suspend fun composeAhead(").substringBefore("/** The colours one widget draws in")
        assertTrue("GlanceRemoteViews()" in ahead)
        assertTrue("Dispatchers.Default" in ahead)
        assertTrue("quiet = true" in ahead)
        assertFalse("renderWidget(" in ahead)
    }

    @Test
    fun `pictures already drawn for these bitmaps are not drawn again, and the wait for them is bounded`() {
        val warm = store.substringAfter("private suspend fun warm(").substringBefore("suspend fun warmResized(")
        assertTrue("warmed.add(" in warm)
        assertTrue("withTimeoutOrNull(WARM_LIMIT_MS)" in warm)
        assertTrue("a cut short drawing may be asked for again", "warmed.remove(" in warm)
    }

    @Test
    fun `both caches draw a picture once, for whoever asks first`() {
        assertTrue("SingleFlight<String, Bitmap>()" in pictures)
        assertTrue("flight.get(key" in pictures)
        assertTrue("SingleFlight<String, Bitmap>()" in glass)
        assertTrue("flight.get(key" in glass)
        assertFalse("the glass cache is big enough for every widget's sizes", "LruCache<String, Bitmap>(6)" in glass)
    }

    @Test
    fun `nothing a look places is stretched to the room it is given`() {
        val at = widget.substringAfter("private fun At(w: Int, h: Int").substringBefore("/** [At] in the widget itself. */")
        assertTrue("absolutePadding(left = x.coerceAtLeast(0).dp, top = spot.y.coerceAtLeast(0).dp)" in at)
        assertTrue("a thing is its spot's size", "modifier.size(spot.w.dp, spot.h.dp)" in at)
        assertFalse("it used to fill what the padding left", "modifier.fillMaxSize()" in at)
        assertFalse("right =" in at)
        assertFalse("bottom =" in at)
    }

    @Test
    fun `the receiver draws a new size's pictures while Glance starts its session`() {
        val changed = receiver.substringAfter("override fun onAppWidgetOptionsChanged(").substringBefore("override fun onUpdate(")
        val ahead = changed.indexOf("WidgetStore.warmResizedSoon(")
        val glance = changed.indexOf("super.onAppWidgetOptionsChanged(")
        assertTrue(changed, ahead in 0 until glance)
        // a resize that goes on wants only the size it has come to
        assertTrue("resizing.remove(appWidgetId)?.cancel()" in store)
    }

    @Test
    fun `the next song's covers and pictures are got ready after the lock is let go, behind what the song that plays is owed`() {
        val set = store.substringAfter("suspend fun setNowPlaying(").substringBefore("\n    }\n")
        val prepared = set.indexOf("prepareNext(context, it)")
        assertTrue(set, prepared > set.indexOf("owedNext.isNotEmpty() && nextPicturesFor("))
        val locked = set.substringAfter("mutex.withLock {").substringBefore("\n        }\n")
        assertFalse("prepareNext(" in locked)
        assertFalse("getReady(" in locked)
        // its fetch is cancellable and its own: a quick skip is not held up behind it
        val ready = store.substringAfter("private suspend fun getReady(").substringBefore("/**\n     * The artwork of the song that plays")
        assertTrue("artMutex.withLock { artInSizes(" in ready)
        assertTrue("warm(context, decoded(context, ahead))" in ready)
        assertTrue("prepareScope.launch" in store.substringAfter("private fun prepareNext("))
    }

    @Test
    fun `the player's song change is the first line the launcher's time is counted from`() {
        val set = store.substringAfter("suspend fun setNowPlaying(").substringBefore("\n    }\n")
        assertEquals(1, Regex("""Song changed to""").findAll(set).count())
        // Once per song, not at each of the four to ten calls a change makes.
        assertTrue("song?.id != lastAsked" in set)
    }

    @Test
    fun `a cover another fetch made is told to the snapshot too`() {
        val pictures = store.substringAfter("private suspend fun picturesFor(").substringBefore("/**\n     * The covers of Up next's songs")
        assertTrue("artOnPhone(context, song.id, ART_NOW_PX) == null" in pictures)
    }

    @Test
    fun `every cover is decoded through the kept bitmaps, so the pictures drawn for them are found`() {
        val decoded = store.substringAfter("private fun decoded(").substringBefore("private fun decode(path")
        assertTrue("coversFor(snapshot, _drawn.value?.art.orEmpty(), ::decodeCover, ::decodeCover)" in decoded)
    }
}
