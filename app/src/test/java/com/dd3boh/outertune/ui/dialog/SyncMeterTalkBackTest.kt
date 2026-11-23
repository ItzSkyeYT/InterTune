/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Listen together, the meter of how far a guest is from the host: whether this phone was behind or ahead was only
 * where the dot sat, the line above says "Catching up, 140 ms apart" with no side, and the two words under the line
 * were read as "Behind" and "Ahead" whatever the dot did (the 0.13 accessibility read, 18 Nov 2026). The meter is now
 * one stop that says the line, then the side, in the words already under it. Semantics only: where the dot is drawn
 * is the business of the right-to-left fix (together-fixes-1118), which this leaves alone.
 */
class SyncMeterTalkBackTest {

    private val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
    private val meter get() = dialog.substringAfter("private fun SyncMeter(").substringBefore("\n}\n")

    @Test
    fun `the meter is one stop, the line above first`() {
        assertTrue(meter, "Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {" in meter)
        val column = meter.substringAfter("semantics(mergeDescendants = true) { }) {")
        assertTrue("the line above comes before the picture", column.indexOf("Text(") in 0 until column.indexOf("Canvas("))
    }

    @Test
    fun `the side is said in the words under the line, as the gap's sign gives it`() {
        assertTrue(meter, "(driftMs ?: 0L) > 0L -> stringResource(R.string.together_behind)" in meter)
        assertTrue(meter, "(driftMs ?: 0L) < 0L -> stringResource(R.string.together_ahead)" in meter)
        assertTrue("in step to the ms, no side", "else -> null" in meter.substringAfter("val side = when {").substringBefore("}"))
        assertTrue(meter, ".height(18.dp)\n                .semantics { if (side != null) contentDescription = side }" in meter)
        // A gap above zero is a phone behind the host: the session counts it from where the host is, and the meter
        // puts it on the side "Behind" is written (meterPlace).
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        assertTrue("driftMs = target - local" in session)
        assertTrue(meterPlace(140) > 0f)
        assertTrue(meterPlace(-140) < 0f)
        assertEquals(0f, meterPlace(0), 0f)
    }

    @Test
    fun `the two words under the line are drawn only, so they no longer contradict the dot`() {
        val words = meter.substringAfter("Row(Modifier.fillMaxWidth()) {")
        assertTrue("no row of words", words != meter)
        assertTrue(words.indexOf("R.string.together_behind") in 0 until words.indexOf("R.string.together_ahead"))
        assertEquals(2, Regex("""color = MaterialTheme\.colorScheme\.onSurfaceVariant,\n(\s+//.*\n)?\s+modifier = Modifier\.clearAndSetSemantics \{ \},""").findAll(words).count())
    }
}
