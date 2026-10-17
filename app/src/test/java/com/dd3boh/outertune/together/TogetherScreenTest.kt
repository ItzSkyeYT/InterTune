/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import androidx.compose.ui.text.AnnotatedString
import com.dd3boh.outertune.ui.dialog.CodeDashes
import com.dd3boh.outertune.ui.dialog.codeTyped
import com.dd3boh.outertune.ui.dialog.initials
import com.dd3boh.outertune.ui.dialog.meterPlace
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Listen together sheet: the code field, the circles' letters, the sync meter's scale, and the Android 8 gate. */
class TogetherScreenTest {

    @Test
    fun `the code field keeps a code's letters, however it is typed or pasted`() {
        assertEquals("K7QXM2PA9DTZ", codeTyped("k7qx-m2pa-9dtz"))
        assertEquals("K7QXM2PA9DTZ", codeTyped("intertune://together/K7QXM2PA9DTZ"))
        assertEquals("K7QX", codeTyped("k7qx-"))
        assertEquals("K7Q", codeTyped("k70q"))                       // a 0 is never in a code
        assertEquals("ABCDEFGHJKLM", codeTyped("ABCDEFGHJKLMNP"))     // twelve at most
        assertEquals("K7", codeTyped("intertune://together/k7q", before = "K7"))    // a link cut short is no code: no letters from it
        assertEquals("K7Q", codeTyped("intertune://k", before = "K7Q"))
    }

    @Test
    fun `a whole invite pasted gives its code, other long text leaves the field alone`() {
        val english = "Listen with me on InterTune: room K7QX-M2PA-9DTZ. Open intertune://together/K7QXM2PA9DTZ, or type the code under Listen together in the player's menu."
        assertEquals("K7QXM2PA9DTZ", codeTyped(english))
        assertEquals("K7QXM2PA9DTZ", codeTyped("Écoute avec moi sur InterTune : salon K7QX-M2PA-9DTZ."))
        assertEquals("K7QXM2PA9DTZ", codeTyped("open intertune://together/k7qxm2pa9dtz now"))
        assertEquals("K7Q", codeTyped("K7Q" + "a long message with no code in it at all", before = "K7Q"))
    }

    @Test
    fun `the dashes are only shown, and the cursor finds its place both ways`() {
        val shown = CodeDashes.filter(AnnotatedString("K7QXM2PA9DTZ"))
        assertEquals("K7QX-M2PA-9DTZ", shown.text.text)
        for (offset in 0..12) {
            assertEquals(offset, shown.offsetMapping.transformedToOriginal(shown.offsetMapping.originalToTransformed(offset)))
        }
        assertEquals("K7QX-M", CodeDashes.filter(AnnotatedString("K7QXM")).text.text)
        assertEquals(6, CodeDashes.filter(AnnotatedString("K7QXM")).offsetMapping.originalToTransformed(5))
    }

    @Test
    fun `circles take two letters, and the meter shows small gaps`() {
        assertEquals("GS", initials("Galaxy S25 Ultra"))
        assertEquals("P5", initials("Pixel 5"))
        assertEquals("SG", initials("sdk_gphone64_x86_64"))
        assertEquals("?", initials(""))
        assertEquals(0f, meterPlace(0), 0f)
        assertEquals(0.25f, meterPlace(25), 1e-4f)                    // in step fills a quarter of each side
        assertEquals(-0.5f, meterPlace(-100), 1e-4f)
        assertEquals(1f, meterPlace(900), 0f)
    }

    @Test
    fun `a guest can suggest from both song menus`() {
        // Quick picks and search results open YouTubeSongMenu, the library SongMenu: the item was in one only.
        for (path in listOf("src/main/java/com/dd3boh/outertune/ui/menu/SongMenu.kt", "src/main/java/com/dd3boh/outertune/ui/menu/YouTubeSongMenu.kt")) {
            assert(File(path).readText().contains("R.string.together_suggest")) { "$path has no Play next in the room" }
        }
    }

    @Test
    fun `rooms are offered from Android 8 only, in the menu and from links`() {
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assert(sheet.contains("Unreleased.LISTEN_TOGETHER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O"))
        for (path in listOf("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt", "src/main/java/com/dd3boh/outertune/MainActivity.kt")) {
            val source = File(path).readText()
            assert(source.contains("togetherAvailable")) { "$path does not ask togetherAvailable" }
            assert(!source.contains("Unreleased.LISTEN_TOGETHER")) { "$path offers rooms below Android 8" }
        }
    }
}
