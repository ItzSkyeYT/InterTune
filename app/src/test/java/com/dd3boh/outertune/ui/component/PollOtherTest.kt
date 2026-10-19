/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.utils.PollChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A poll's "Other" choice: a box for the person's own words, sent with the answer. */
class PollOtherTest {

    private val poll = PollChecker.Poll(
        id = "next", banner = "b", question = "q", body = null, imageUrl = null, multiple = true,
        options = listOf(
            PollChecker.Option("watch", "An app for my watch", null),
            PollChecker.Option("other", "Other", null, other = true),
        ),
    )

    @Test
    fun `Send needs a pick, and words once Other is one of the picks`() {
        assertFalse(canSend(poll, emptyList(), ""))
        assertTrue(canSend(poll, listOf("watch"), ""))
        assertFalse("Other picked, nothing written", canSend(poll, listOf("watch", "other"), "  "))
        assertTrue(canSend(poll, listOf("other"), "Offline lyrics"))
        assertTrue(otherChosen(poll, listOf("other")))
        assertFalse(otherChosen(poll, listOf("watch")))
    }

    @Test
    fun `the words travel with the answer, and the box and its notice have their words in both languages`() {
        val checker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()
        assertTrue(checker.contains("optBoolean(\"other\", false)"))
        assertTrue(checker.contains("put(\"text\", it.take(MAX_PROPERTY_CHARS))"))
        val home = File("src/main/java/com/dd3boh/outertune/ui/screens/HomeScreen.kt").readText()
        assertTrue(home.contains("pollChecker.answer(poll, chosen, otherText)"))
        val ui = File("src/main/java/com/dd3boh/outertune/ui/component/PollUi.kt").readText()
        assertTrue("the box opens under an Other choice once picked", ui.contains("option.other && option.id in selected"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("poll_other_hint", "poll_other_notice")) {
                assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
            }
        }
    }
}
