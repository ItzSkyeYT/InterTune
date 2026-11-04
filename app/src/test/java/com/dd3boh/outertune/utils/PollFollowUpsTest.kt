/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.Polls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Follow-up questions in a poll ("then" on an option): read so that a mistake in the document costs
 * that follow-up and nothing else, asked in the order of the options, and sent in the one answer as
 * "<optionId>/<followUpOptionId>", with nothing else added.
 */
class PollFollowUpsTest {

    private val then = """{"question": "Which details?", "body": "Tick all that apply.", "multiple": true, "options": [
        {"id": "spacing", "label": "Spacing and alignment"},
        {"id": "icons", "label": "Icons"},
        {"id": "other", "label": "Something else", "other": true, "placeholder": "What would you polish?"}
    ]}"""

    private fun option(id: String, label: String = id, then: PollChecker.FollowUp? = null, other: Boolean = false) =
        PollChecker.Option(id, label, null, other = other, then = then)

    private val details = PollFollowUps.read("polish", then, enabled = true)!!
    private val colours = PollChecker.FollowUp("Which colours?", null, listOf(option("dark"), option("light")), multiple = false)

    private val poll = PollChecker.Poll(
        id = "look-2026-11", banner = "b", question = "Should InterTune get a new look?", body = null, imageUrl = null,
        multiple = false,
        options = listOf(
            option("keep", "Keep it as it is"),
            option("polish", "Keep it, but polish the details", then = details),
            option("new", "A whole new look", then = colours),
            option("other", "Something else", other = true),
        ),
    )
    private val polish = poll.options[1]
    private val new = poll.options[2]

    @Test
    fun `a then is read with its question, its words and its choices`() {
        assertEquals("Which details?", details.question)
        assertEquals("Tick all that apply.", details.body)
        assertTrue(details.multiple)
        assertEquals(listOf("spacing", "icons", "other"), details.options.map { it.id })
        assertEquals("Spacing and alignment", details.options[0].label)
        assertTrue(details.options[2].other)
        assertEquals("What would you polish?", details.options[2].placeholder)
        assertFalse(PollFollowUps.read("polish", """{"question": "Q?", "options": [{"id": "a", "label": "A"}]}""", true)!!.multiple)
    }

    @Test
    fun `a then that cannot be read is dropped, and only the then`() {
        for (bad in listOf(
            "not json", "[]", "\"Which?\"",
            """{"options": [{"id": "a", "label": "A"}]}""",
            """{"question": "  ", "options": [{"id": "a", "label": "A"}]}""",
            """{"question": "Q?"}""",
            """{"question": "Q?", "options": []}""",
            """{"question": "Q?", "options": "a, b"}""",
            """{"question": "Q?", "options": [{"id": "a"}, {"label": "B"}, 3, null]}""",
        )) {
            assertNull(bad, PollFollowUps.read("polish", bad, enabled = true))
        }
        // A choice that cannot be read is skipped and the follow-up kept: an id holding the
        // separator, an id used twice, a choice without words.
        val some = PollFollowUps.read(
            "polish",
            """{"question": "Q?", "options": [{"id": "a/b", "label": "Slash"}, {"id": "a,b", "label": "Comma"},
                {"id": "a", "label": "A"}, {"id": "a", "label": "Again"}, {"id": "b", "label": ""}]}""",
            true,
        )!!
        assertEquals(listOf("a" to "A"), some.options.map { it.id to it.label })
        // An option whose own id holds the separator gets no follow-up: its picks could not be sent.
        assertNull(PollFollowUps.read("a/b", then, true))
        // The poll's reading hands over what the document says, and a then that is not an object is none.
        val checker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()
        assertTrue(checker.contains("then = PollFollowUps.read(id, oo.optJSONObject(\"then\")?.toString(), Unreleased.POLL_FOLLOW_UPS),"))
        val reader = File("src/main/java/com/dd3boh/outertune/utils/PollFollowUps.kt").readText()
        assertTrue("nothing it meets can throw into the poll's reading", reader.contains("return runCatching {"))
    }

    @Test
    fun `with follow-ups off, then is not read at all, as in every older build`() {
        assertNull(PollFollowUps.read("polish", then, enabled = false))
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        assertTrue(flags.contains("val POLL_FOLLOW_UPS = BuildConfig.DEBUG"))
        // No then, so no step after the first question, and the answer is the first one alone.
        val plain = poll.copy(options = poll.options.map { it.copy(then = null) })
        assertTrue(PollFollowUps.steps(plain, listOf("polish")).isEmpty())
        assertEquals(listOf("polish"), PollFollowUps.answerIds(plain, listOf("polish"), listOf("polish/icons")))
    }

    @Test
    fun `one level only, a then inside a then is never read`() {
        val nested = PollFollowUps.read(
            "polish",
            """{"question": "Q?", "options": [{"id": "a", "label": "A", "then": {"question": "Deeper?", "options": [{"id": "x", "label": "X"}]}}]}""",
            true,
        )!!
        assertNull(nested.options.single().then)
        assertTrue(details.options.all { it.then == null })
    }

    @Test
    fun `the questions after the first come in the order of the options, for what was picked`() {
        val several = poll.copy(multiple = true)
        assertTrue(PollFollowUps.steps(several, listOf("keep", "other")).isEmpty())
        assertEquals(listOf("polish"), PollFollowUps.steps(several, listOf("polish")).map { it.id })
        assertEquals(listOf("polish", "new"), PollFollowUps.steps(several, listOf("new", "keep", "polish")).map { it.id })
    }

    @Test
    fun `in a follow-up one choice replaces the last and several toggle`() {
        var deeper = PollFollowUps.toggle(polish, details.options[0], emptyList())
        deeper = PollFollowUps.toggle(polish, details.options[1], deeper)
        assertEquals(listOf("polish/spacing", "polish/icons"), deeper)
        deeper = PollFollowUps.toggle(polish, details.options[0], deeper)
        assertEquals(listOf("polish/icons"), deeper)
        deeper = PollFollowUps.toggle(new, colours.options[0], deeper)
        deeper = PollFollowUps.toggle(new, colours.options[1], deeper)
        assertEquals(listOf("polish/icons", "new/light"), deeper)
        assertEquals(listOf("icons"), PollFollowUps.picksIn(polish, deeper))
        assertEquals(listOf("light"), PollFollowUps.picksIn(new, deeper))
    }

    @Test
    fun `a follow-up never costs the first answer, but an Other picked in it needs words`() {
        assertTrue(PollFollowUps.canGoOn(details, emptyList(), ""))
        assertTrue(PollFollowUps.canGoOn(details, listOf("icons"), ""))
        assertFalse(PollFollowUps.canGoOn(details, listOf("icons", "other"), "  "))
        assertTrue(PollFollowUps.canGoOn(details, listOf("other"), "Bigger buttons"))
    }

    @Test
    fun `what is sent is the first picks and each follow-up pick as optionId slash followUpOptionId, nothing else`() {
        // A pick under an option no longer picked, and one that is no choice of the follow-up, stay home.
        val ids = PollFollowUps.answerIds(poll, listOf("polish"), listOf("polish/spacing", "new/dark", "polish/icons", "polish/nope"))
        assertEquals(listOf("polish", "polish/spacing", "polish/icons"), ids)
        val sent = PollChecker.answerData(poll, ids, null, "0.13")
        assertEquals(
            mapOf(
                "poll" to "Should InterTune get a new look?",
                "poll_id" to "look-2026-11",
                // The labels are the first question's, so its counts read the same from every build.
                "answer" to "Keep it, but polish the details",
                "answer_id" to "polish,polish/icons,polish/spacing",
                "version" to "0.13",
            ),
            sent,
        )
        // The gate itself lets nothing else through.
        assertEquals("polish,polish/icons", PollChecker.answerData(poll, listOf("polish", "new/dark", "ghost", "polish/icons", "/icons"), null, "v")["answer_id"])
        // Said where what leaves the device is written down.
        val checker = File("src/main/java/com/dd3boh/outertune/utils/PollChecker.kt").readText()
        assertTrue(checker.contains("a follow-up question's picks go in that same event, as \"<optionId>/<followUpOptionId>\""))
    }

    @Test
    fun `a poll without follow-ups sends what it always sent`() {
        val plain = PollChecker.Poll(
            id = "next", banner = "b", question = "q", body = null, imageUrl = null, multiple = true,
            options = listOf(option("watch", "An app for my watch"), option("other", "Other", other = true)),
        )
        assertTrue(PollFollowUps.steps(plain, listOf("watch", "other")).isEmpty())
        val ids = PollFollowUps.answerIds(plain, listOf("watch", "other"), emptyList())
        assertEquals(listOf("watch", "other"), ids)
        assertEquals(
            mapOf(
                "poll" to "q", "poll_id" to "next", "answer" to "An app for my watch, Other",
                "answer_id" to "other,watch", "version" to "0.12.7", "text" to "Offline lyrics",
            ),
            PollChecker.answerData(plain, ids, PollFollowUps.words(plain, ids, "  Offline lyrics ", emptyList(), emptyMap()), "0.12.7"),
        )
    }

    @Test
    fun `the words of each Other picked go in one answer, the first question's first, within its room`() {
        val several = poll.copy(multiple = true)
        val boxes = mapOf("polish" to "Bigger buttons", "new" to "never sent")
        assertEquals(
            "Glass everywhere\nBigger buttons",
            PollFollowUps.words(several, listOf("polish", "other"), " Glass everywhere ", listOf("polish/other"), boxes),
        )
        // Words in a box whose Other is not picked stay on screen and are not sent.
        assertNull(PollFollowUps.words(several, listOf("polish"), "Glass", listOf("polish/icons"), boxes))
        // One box has all of an answer's room; two share it.
        assertEquals(Polls.MAX_TEXT_CHARS, PollFollowUps.room(several, listOf("polish"), "", listOf("polish/other"), emptyMap(), "polish"))
        assertEquals(
            Polls.MAX_TEXT_CHARS - "Glass everywhere".length - 1,
            PollFollowUps.room(several, listOf("other", "polish"), "Glass everywhere", listOf("polish/other"), boxes, "polish"),
        )
        assertEquals(
            Polls.MAX_TEXT_CHARS - "Bigger buttons".length - 1,
            PollFollowUps.room(several, listOf("other", "polish"), "Glass everywhere", listOf("polish/other"), boxes, null),
        )
    }

    @Test
    fun `the card asks the follow-ups after the first question, with Next and Back, then one Send`() {
        val ui = File("src/main/java/com/dd3boh/outertune/ui/component/PollUi.kt").readText()
        assertTrue(ui.contains("val steps = PollFollowUps.steps(poll, selected)"))
        assertEquals("one send, of the whole answer", 2, ui.split("onSubmit(").size)
        assertTrue(ui.contains("val send = { onSubmit(PollFollowUps.answerIds(poll, selected, deeper), words) }"))
        assertTrue("Next until the last question", ui.contains("next = !last"))
        assertTrue(ui.contains("if (next) R.string.action_next else R.string.poll_send"))
        assertTrue("Back on a follow-up, in place of Not now", ui.contains("if (onBack != null) R.string.action_back else R.string.poll_not_now"))
        // The back key too, from inside the dialog, which is the window that hears it.
        val handler = ui.indexOf("BackHandler(enabled = at > 0) { step = at - 1 }")
        assertTrue(handler > ui.indexOf("Dialog(\n        onDismissRequest = onClose"))
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("action_next", "action_back")) assertTrue("$name missing from $path", strings.contains("name=\"$name\""))
        }
    }
}
