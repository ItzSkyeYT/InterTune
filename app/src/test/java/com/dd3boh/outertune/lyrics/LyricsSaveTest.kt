/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Save beside Share in the lyrics card dialog (asked for on 2 Nov 2026): the picture or the video
 * kept on the phone instead of sent. Straight into Pictures/InterTune or Movies/InterTune from
 * Android 10, through the system's own save screen before that.
 */
class LyricsSaveTest {
    private fun source(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()

    @Test
    fun `pictures and videos each go to a folder of the app's own`() {
        assertEquals("Pictures/InterTune", LyricsSave.place(video = false))
        assertEquals("Movies/InterTune", LyricsSave.place(video = true))
    }

    @Test
    fun `Save stands between Cancel and Share, and saves what Share would send`() {
        val card = source("ui/component/LyricsCard.kt")
        val cancel = card.indexOf("Text(stringResource(android.R.string.cancel))")
        val save = card.indexOf("Text(stringResource(R.string.save))")
        val share = card.indexOf("Text(stringResource(R.string.share))")
        assertTrue("all three there", cancel > 0 && save > 0 && share > 0)
        assertTrue("in that order", cancel < save && save < share)
        assertTrue(card.contains("if (asVideo) video?.let { makeVideo(it, keep = true) } else savePicture()"))
    }

    @Test
    fun `older Android asks where, and the dialog waits for the answer`() {
        val card = source("ui/component/LyricsCard.kt")
        assertTrue(card.contains("ActivityResultContracts.CreateDocument(LyricsSave.TYPE_PICTURE)"))
        assertTrue(card.contains("ActivityResultContracts.CreateDocument(LyricsSave.TYPE_VIDEO)"))
        // A video for the save screen does not close the dialog: the answer comes back to it.
        assertTrue(card.contains("if (!keep) onShared()"))
    }

    @Test
    fun `a file in the gallery is hidden until it is whole, and taken away when it cannot be`() {
        val save = source("lyrics/LyricsSave.kt")
        assertTrue(save.contains("put(MediaStore.MediaColumns.IS_PENDING, 1)"))
        assertTrue(save.contains("put(MediaStore.MediaColumns.IS_PENDING, 0)"))
        assertTrue(save.contains("put(MediaStore.MediaColumns.RELATIVE_PATH, place(video))"))
        assertTrue(save.contains("resolver.delete(uri, null, null)"))
    }

    @Test
    fun `the message says where it went, in English and French`() {
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue(en.contains("<string name=\"lyrics_saved\" translatable=\"false\">Saved: %1\$s</string>"))
        assertTrue(fr.contains("<string name=\"lyrics_saved\">Enregistré : %1\$s</string>"))
    }
}
