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
import com.dd3boh.outertune.ui.dialog.meterX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * The meter's dot was drawn on a Canvas, which does not turn round in a right-to-left layout, while the "Behind" and
     * "Ahead" under it do: in Arabic, Hebrew or Persian a phone behind showed its dot under "Ahead" (review of 0.13,
     * 18 Nov 2026).
     */
    @Test
    fun `the meter's dot is behind on the side Behind is written, right to left included`() {
        val behind = meterPlace(120)     // this phone 120 ms behind the host
        // left to right: "Behind" at the left, and the dot with it
        assertTrue(meterX(behind, rtl = false) < 0f)
        assertTrue(meterX(-behind, rtl = false) > 0f)
        // right to left: the row of words turns round, "Behind" at the right, and the dot follows it
        assertTrue(meterX(behind, rtl = true) > 0f)
        assertTrue(meterX(-behind, rtl = true) < 0f)
        assertEquals(0f, meterX(meterPlace(0), rtl = true), 0f)
        // drawn from it with the layout's own direction, "Behind" being the row's first word (its start side)
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val meter = dialog.substringAfter("private fun SyncMeter(").substringBefore("@Composable")
        assertTrue("val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl" in meter)
        assertTrue("center = Offset(half + meterX(place, rtl) * reach, y)" in meter)
        val labels = meter.substringAfter("Row(Modifier.fillMaxWidth())")
        assertTrue(labels.indexOf("R.string.together_behind") in 0 until labels.indexOf("R.string.together_ahead"))
    }

    /**
     * The sheet's picture passed its animated playhead to both phones as a number, so they composed again on every frame,
     * covers included, for as long as it showed (performance review, 18 Nov 2026). Read only where it is drawn now.
     */
    @Test
    fun `the picture's playhead is read only where it is drawn`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val picture = dialog.substringAfter("private fun InStepPicture(").substringBefore("@Composable")
        assertEquals(2, Regex("""PhoneSketch\(cover\) \{ head \}""").findAll(picture).count())
        val sketch = dialog.substringAfter("private fun PhoneSketch(").substringBefore("@Composable")
        // The cover is whatever the picture loader takes (a song from a file's own picture is not a web address).
        assertTrue(sketch.startsWith("cover: Any?, head: () -> Float)"))
        assertTrue("val x = size.width * head()" in sketch.substringAfter("Canvas(Modifier.width(46.dp).height(8.dp)) {"))
        assertEquals("read once, in the playhead's own canvas", 1, Regex("""head\(\)""").findAll(sketch).count())
    }

    /** The room's live dot pulsed whether or not a relay answered: still while none does (performance review). */
    @Test
    fun `the live dot is still while no relay answers`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val dot = dialog.substringAfter("private fun LiveDot(").substringBefore("@Composable")
        val still = dot.substringAfter("if (!connected) {").substringBefore("} else {")
        assertTrue("drawCircle(grey, radius = size.minDimension / 2)" in still)
        assertTrue("rememberInfiniteTransition" !in still)
        assertTrue(dot.indexOf("rememberInfiniteTransition") > dot.indexOf("} else {"))
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
        for (path in listOf(
            "src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt",
            "src/main/java/com/dd3boh/outertune/MainActivity.kt",
            "src/main/java/com/dd3boh/outertune/ui/screens/HomeScreen.kt",
        )) {
            val source = File(path).readText()
            assert(source.contains("togetherAvailable")) { "$path does not ask togetherAvailable" }
            assert(!source.contains("Unreleased.LISTEN_TOGETHER")) { "$path offers rooms below Android 8" }
        }
    }
}
