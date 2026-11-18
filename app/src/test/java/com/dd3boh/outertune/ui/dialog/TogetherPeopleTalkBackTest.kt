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
 * Listen together, the people in a room: TalkBack read each circle's initials first ("M B, Galaxy S25 Ultra, Host"),
 * and the owner's tap that opens Give control and Remove from the room had no name (the 0.13 accessibility read,
 * 18 Nov 2026). The initials are now only drawn, each person is one stop, and the tap says it opens their options.
 */
class TogetherPeopleTalkBackTest {

    private val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
    private val person get() = dialog.substringAfter("private fun Person(").substringBefore("\n}\n")

    @Test
    fun `the initials are drawn, not read`() {
        val circle = person.substringAfter("Text(\n                    initials,").substringBefore("\n                )")
        assertTrue(circle, "modifier = Modifier.clearAndSetSemantics { }," in circle)
        // the name and the phone's name under the circle are what is read, and the star still says Host
        assertTrue(person, "contentDescription = stringResource(R.string.together_host)" in person)
        assertTrue(person, "Text(name, style = MaterialTheme.typography.labelMedium" in person)
    }

    @Test
    fun `the owner's tap says what it opens, and the long press stays for a finger`() {
        assertTrue(person, ".combinedClickable(onClickLabel = stringResource(R.string.options), onClick = onPress, onLongClick = onPress)" in person)
        assertEquals("one tap, one way in", 1, Regex("""combinedClickable\(""").findAll(person).count())
    }

    @Test
    fun `a person nobody can tap is still one stop, and nothing in the row moves`() {
        assertTrue(person, "} else Modifier.semantics(mergeDescendants = true) { }" in person)
        assertTrue(person, "modifier = Modifier.width(76.dp).then(pressable)" in person)
        assertTrue(person, ".size(48.dp)" in person)
    }
}
