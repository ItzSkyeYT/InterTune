/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.UpdateCheckEnabledKey
import com.dd3boh.outertune.constants.UsageCountEnabledKey
import com.dd3boh.outertune.engine.SimilarSources
import com.dd3boh.outertune.ui.screens.SetupQuestion.COUNT
import com.dd3boh.outertune.ui.screens.SetupQuestion.LASTFM
import com.dd3boh.outertune.ui.screens.SetupQuestion.UPDATES
import com.dd3boh.outertune.ui.screens.SetupQuestion.WORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The last page of the short setup: one question at a time in a card, a yes and a no under it,
 * the answers given so far as rows above it, and the tick that stores them.
 *
 * It was a list of five switches, and read as a wall to get past (11 Oct 2026). What the page
 * shows follows from two things, the answers given and the question in the card, and what the
 * tick stores follows from the first. Both are held here without a screen.
 */
class SetupQuestionsTest {

    private val both = listOf(false, true)

    /** The page on an install that was never asked anything, in a build that can ask Last.fm's question. */
    private val fresh = SetupQuestions.from(mutablePreferencesOf(), lastFmAskable = true)

    /** The rows above the card: the questions that have an answer, in the order they are put. */
    private fun rows(page: SetupQuestions) = page.asked.filter { it in page.answers }

    /** Whether the catch-up screen would open over this store. */
    private fun owed(prefs: MutablePreferences, lastFmAskable: Boolean = true) = catchUpOwed(
        updates = prefs[UpdateCheckEnabledKey],
        questions = prefs[PollsEnabledKey],
        news = prefs[AnnouncementsEnabledKey],
        count = prefs[UsageCountEnabledKey],
        lastFmOwed = lastFmAskable && !SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]),
    )

    @Test
    fun `a new install is put the first question, with no row above it`() {
        assertEquals(listOf(UPDATES, WORD, COUNT, LASTFM), fresh.asked)
        assertEquals(emptyList<SetupQuestion>(), rows(fresh))
        assertEquals(UPDATES, fresh.open)
    }

    @Test
    fun `a build that cannot ask Last-fm puts three questions`() {
        // Whatever is stored for it: the question is not this build's to put.
        val page = SetupQuestions.from(mutablePreferencesOf(SimilarSourceKey to SimilarSource.BOTH.name), lastFmAskable = false)
        assertEquals(listOf(UPDATES, WORD, COUNT), page.asked)
        assertEquals(emptyList<SetupQuestion>(), rows(page))
        // And it takes no answer to it.
        assertEquals(page, page.answer(LASTFM, yes = true))
        assertNull(page.answer(UPDATES, true).answer(WORD, true).answer(COUNT, true).choices().lastFm)
    }

    @Test
    fun `each answer brings the next question, and the last one leaves the card to the closing line`() {
        var page = fresh
        for ((at, question) in fresh.asked.withIndex()) {
            assertEquals(question, page.open)
            assertEquals(fresh.asked.take(at), rows(page))
            page = page.answer(question, yes = at % 2 == 0)
            assertEquals(at % 2 == 0, page.answers[question])
        }
        // Every question has its row, and none is in the card: the page says that is all.
        assertEquals(fresh.asked, rows(page))
        assertNull(page.open)

        // Three questions end the same way, one answer sooner.
        var short = SetupQuestions.from(mutablePreferencesOf(), lastFmAskable = false)
        for (question in short.asked) short = short.answer(question, yes = false)
        assertNull(short.open)
    }

    @Test
    fun `there is a question in the card for as long as one has no answer`() {
        // Whichever are answered, and in whatever order.
        for (first in fresh.asked) for (second in fresh.asked) for (third in fresh.asked) {
            val page = fresh.answer(first, true).answer(second, false).answer(third, true)
            val left = fresh.asked.filter { it !in setOf(first, second, third) }
            assertEquals("$first, $second, $third", left.firstOrNull(), page.open)
        }
    }

    @Test
    fun `a word from InterTune is one answer to news and to questions`() {
        for (yes in both) {
            val choices = fresh.answer(WORD, yes).choices()
            assertEquals(yes, choices.news)
            assertEquals(yes, choices.questions)
            // And to nothing else.
            assertEquals(SetupChoices.UPDATES_DEFAULT, choices.updates)
            assertFalse(choices.count)
            assertEquals(false, choices.lastFm)
        }
    }

    @Test
    fun `the tick stores each answer as it was given, in all sixteen ways`() {
        for (updates in both) for (word in both) for (count in both) for (lastFm in both) {
            val page = fresh.answer(UPDATES, updates).answer(WORD, word).answer(COUNT, count).answer(LASTFM, lastFm)
            val at = "updates $updates, a word $word, count $count, Last.fm $lastFm"
            assertNull(at, page.open)

            val prefs = mutablePreferencesOf()
            page.choices().store(prefs)
            assertEquals(at, updates, prefs[UpdateCheckEnabledKey])
            assertEquals(at, word, prefs[PollsEnabledKey])
            assertEquals(at, word, prefs[AnnouncementsEnabledKey])
            assertEquals(at, count, prefs[UsageCountEnabledKey])
            // What the old card's two buttons write: a yes is Both, a no is YouTube alone.
            assertEquals(at, if (lastFm) SimilarSource.BOTH.name else SimilarSource.YOUTUBE.name, prefs[SimilarSourceKey])
            assertFalse("nothing left to ask after the tick: $at", owed(prefs))
        }
    }

    @Test
    fun `the tick with nothing answered is a no to every question, and nothing is left to ask`() {
        val prefs = mutablePreferencesOf()
        SetupQuestions.from(prefs, lastFmAskable = true).choices().store(prefs)
        // What a switch nobody touched stored: off.
        assertEquals(SetupChoices.UPDATES_DEFAULT, prefs[UpdateCheckEnabledKey])
        assertEquals(false, prefs[PollsEnabledKey])
        assertEquals(false, prefs[AnnouncementsEnabledKey])
        assertEquals(false, prefs[UsageCountEnabledKey])
        // Last.fm too. Left unanswered it would be owed, and the catch-up screen would open as
        // setup closes, to put the question that had just been passed over.
        assertEquals(SimilarSource.YOUTUBE.name, prefs[SimilarSourceKey])
        assertFalse(owed(prefs))
        assertFalse(owed(prefs, lastFmAskable = false))
    }

    @Test
    fun `the tick part way through stores the answers given and a no for the rest`() {
        val prefs = mutablePreferencesOf()
        fresh.answer(UPDATES, true).answer(WORD, true).choices().store(prefs)
        assertEquals(true, prefs[UpdateCheckEnabledKey])
        assertEquals(true, prefs[PollsEnabledKey])
        assertEquals(true, prefs[AnnouncementsEnabledKey])
        assertEquals(false, prefs[UsageCountEnabledKey])
        assertEquals(SimilarSource.YOUTUBE.name, prefs[SimilarSourceKey])

        // Every question but Last.fm's answered: that one is a no like the rest, and nothing is owed.
        val last = mutablePreferencesOf()
        fresh.answer(UPDATES, false).answer(WORD, false).answer(COUNT, true).choices().store(last)
        assertEquals(SimilarSource.YOUTUBE.name, last[SimilarSourceKey])
        assertFalse(owed(last))
    }

    @Test
    fun `the tick stores the answers and nothing else`() {
        val prefs = mutablePreferencesOf()
        fresh.answer(UPDATES, true).answer(WORD, true).answer(COUNT, true).answer(LASTFM, true).choices().store(prefs)
        assertEquals(
            setOf(UpdateCheckEnabledKey, PollsEnabledKey, AnnouncementsEnabledKey, UsageCountEnabledKey, SimilarSourceKey),
            prefs.asMap().keys,
        )
    }

    @Test
    fun `setup opened again shows the answers given before, and the tick leaves them as they were`() {
        for (source in SimilarSource.entries) for (word in both) {
            val prefs = mutablePreferencesOf(
                UpdateCheckEnabledKey to true,
                PollsEnabledKey to word,
                AnnouncementsEnabledKey to word,
                UsageCountEnabledKey to false,
                SimilarSourceKey to source.name,
            )
            val before = prefs.asMap().toMap()

            val page = SetupQuestions.from(prefs, lastFmAskable = true)
            assertEquals(
                mapOf(UPDATES to true, WORD to word, COUNT to false, LASTFM to (source != SimilarSource.YOUTUBE)),
                page.answers,
            )
            // Four rows and the closing line: one can be changed, or the tick tapped.
            assertEquals(page.asked, rows(page))
            assertNull(page.open)

            // Last.fm alone must not come back as Both because a yes can only say yes.
            page.choices().store(prefs)
            assertEquals(before, prefs.asMap())
        }
    }

    @Test
    fun `the old Last-fm switch counts as an answer and keeps its meaning through the tick`() {
        val prefs = mutablePreferencesOf(SimilarFromLastFmKey to true)
        val page = SetupQuestions.from(prefs, lastFmAskable = true)
        assertEquals(true, page.answers[LASTFM])
        // The three others were never asked, so the card is on the first of them.
        assertEquals(UPDATES, page.open)
        page.choices().store(prefs)
        assertEquals(SimilarSource.LASTFM, SimilarSources.stored(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]))
    }

    @Test
    fun `an install that answered some questions is put the first it never answered`() {
        // A backup from before the count and the Last.fm question existed.
        val prefs = mutablePreferencesOf(UpdateCheckEnabledKey to false, PollsEnabledKey to true, AnnouncementsEnabledKey to true)
        val before = prefs.asMap().toMap()
        val page = SetupQuestions.from(prefs, lastFmAskable = true)
        assertEquals(mapOf(UPDATES to false, WORD to true), page.answers)
        assertEquals(COUNT, page.open)
        // Opening the page writes nothing.
        assertEquals(before, prefs.asMap())
    }

    @Test
    fun `news and questions set apart are one question that has no answer yet`() {
        for ((questions, news) in listOf(true to false, false to true)) {
            val prefs = mutablePreferencesOf(
                UpdateCheckEnabledKey to true,
                PollsEnabledKey to questions,
                AnnouncementsEnabledKey to news,
                UsageCountEnabledKey to true,
                SimilarSourceKey to SimilarSource.BOTH.name,
            )
            val page = SetupQuestions.from(prefs, lastFmAskable = true)
            // No row for it, and it is the one in the card.
            assertEquals(listOf(UPDATES, COUNT, LASTFM), rows(page))
            assertEquals(WORD, page.open)

            // Left alone it is a no to both, like any question nobody answered.
            assertFalse(page.choices().questions)
            assertFalse(page.choices().news)
            // Answered, it is that answer to both, and the page is done.
            for (yes in both) {
                val answered = page.answer(WORD, yes)
                assertEquals(yes, answered.choices().questions)
                assertEquals(yes, answered.choices().news)
                assertNull(answered.open)
            }
        }
    }

    @Test
    fun `news and questions have no answer either while one of them was never asked`() {
        val half = listOf(
            mutablePreferencesOf(PollsEnabledKey to false),
            mutablePreferencesOf(PollsEnabledKey to true),
            mutablePreferencesOf(AnnouncementsEnabledKey to false),
            mutablePreferencesOf(AnnouncementsEnabledKey to true),
        )
        for (prefs in half) {
            assertFalse(prefs.toString(), WORD in SetupQuestions.from(prefs, lastFmAskable = true).answers)
        }
    }

    @Test
    fun `a row puts its question back in the card, and the answer stands until another is given`() {
        var page = fresh.answer(UPDATES, true).answer(WORD, false)
        assertEquals(COUNT, page.open)

        page = page.reopen(UPDATES)
        assertEquals(UPDATES, page.open)
        // Looking at it again changes nothing: the row still says yes, and the tick stores yes.
        assertEquals(true, page.answers[UPDATES])
        assertEquals(listOf(UPDATES, WORD), rows(page))
        assertTrue(page.choices().updates)

        // Another answer replaces it, and the card goes back to the question that was waiting.
        page = page.answer(UPDATES, false)
        assertEquals(false, page.answers[UPDATES])
        assertFalse(page.choices().updates)
        assertEquals(COUNT, page.open)
    }

    @Test
    fun `the same answer given again changes nothing and moves on`() {
        val page = fresh.answer(UPDATES, true).answer(WORD, false)
        assertEquals(page, page.reopen(WORD).answer(WORD, false))
    }

    @Test
    fun `with every question answered, changing one brings the closing line back`() {
        var page = fresh.answer(UPDATES, true).answer(WORD, true).answer(COUNT, true).answer(LASTFM, true)
        for (question in page.asked) {
            page = page.reopen(question)
            assertEquals(question, page.open)
            assertEquals(page.asked, rows(page))
            page = page.answer(question, yes = false)
            assertNull(page.open)
        }
        assertEquals(SetupChoices(updates = false, questions = false, news = false, count = false, lastFm = false), page.choices())
    }

    @Test
    fun `only a question that has a row can be put back in the card`() {
        val page = fresh.answer(UPDATES, true)
        assertEquals(page, page.reopen(COUNT))
        assertEquals(page, page.reopen(LASTFM))
        // The one in the card stays there when its own turn has not come.
        assertEquals(WORD, page.reopen(WORD).open)
    }

    @Test
    fun `the page comes back from a rotation as it stood`() {
        val marks = listOf(null, false, true)
        for (lastFmAskable in both) {
            val asked = SetupQuestions.from(mutablePreferencesOf(), lastFmAskable).asked
            for (updates in marks) for (word in marks) for (count in marks) for (lastFm in if (lastFmAskable) marks else listOf(null)) {
                val answers = listOf(UPDATES to updates, WORD to word, COUNT to count, LASTFM to lastFm)
                    .mapNotNull { (question, answer) -> answer?.let { question to it } }
                    .toMap()
                // Any question may be in the card, and none only once every one is answered.
                val inCard: List<SetupQuestion?> = if (asked.all { it in answers }) asked + null else asked
                for (open in inCard) {
                    val page = SetupQuestions(asked, answers, open)
                    assertEquals(page.saved(), page, SetupQuestions.ofSaved(page.saved(), lastFmAskable))
                }
            }
        }
    }

    @Test
    fun `anything else that comes back is not a page, and the page starts from what is stored`() {
        for (saved in listOf("", "1", "10-", "10-10-", "10x-0", "10--4", "10--x", "-----")) {
            assertNull(saved, SetupQuestions.ofSaved(saved, lastFmAskable = true))
        }
        // The page of a build with another number of questions.
        val four = fresh.answer(UPDATES, true).saved()
        val three = SetupQuestions.from(mutablePreferencesOf(), lastFmAskable = false).answer(UPDATES, true).saved()
        assertNull(SetupQuestions.ofSaved(four, lastFmAskable = false))
        assertNull(SetupQuestions.ofSaved(three, lastFmAskable = true))
        assertEquals(fresh.answer(UPDATES, true), SetupQuestions.ofSaved(four, lastFmAskable = true))
    }

    // What follows reads the page's source: it is a screen, and no test here can tap it.

    private val setup = File("src/main/java/com/dd3boh/outertune/ui/screens/ShortSetup.kt").readText()

    /** The text without its comments, so a sentence that names a thing is not taken for the thing. */
    private val code = setup
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `no switch is left on the page`() {
        assertFalse("SwitchPreference" in code)
        assertFalse(Regex("""\bSwitch\(""").containsMatchIn(code))
        // Nor the two texts only the switches had.
        for (gone in listOf("setup_questions_line", "setup_news_line")) {
            assertFalse(gone, gone in code)
            for (file in listOf("values/strings-ot.xml", "values-fr/strings-ot.xml")) {
                assertFalse("$gone is still a string in $file", gone in File("src/main/res/$file").readText())
            }
        }
    }

    @Test
    fun `only the tick stores the answers`() {
        // An answer tapped is kept by the page and written nowhere: setup left by restoring a
        // backup must leave no answer behind it.
        assertEquals(1, Regex("""\.store\(""").findAll(code).count())
        val finish = code.substringAfter("val finish: () -> Unit = {").substringBefore("var settledPage")
        assertTrue(Regex("""questions\.choices\(\)""").containsMatchIn(finish))
        assertTrue(Regex("""dataStore\.edit \{ answers\.store\(it\) }""").containsMatchIn(finish))
        // The two other writes of the screen are the mark of a first setup and the one in finish.
        assertEquals(3, Regex("""dataStore\.edit\b""").findAll(code).count())
    }

    @Test
    fun `a question takes no answer in its first moment, nor from a card on its way out`() {
        // The two answers stand where the answers of the question before stood, so the second
        // tap of a double tap would answer a question nobody had read.
        assertTrue(
            Regex("""LaunchedEffect\(questions\.open\) \{\s*delay\(SETTLE_MS\)\s*settledQuestion = questions\.open\s*}""")
                .containsMatchIn(code)
        )
        val answer = code.substringAfter("val answer: (SetupQuestion, Boolean) -> Unit = {").substringBefore("val reopen")
        assertTrue(answer, "if (live && question == questions.open && question == settledQuestion) {" in answer)
        // And no answer is taken anywhere else.
        assertEquals(1, Regex("""questions\.answer\(""").findAll(code).count())
        assertEquals(1, Regex("""questions\.reopen\(""").findAll(code).count())
    }

    @Test
    fun `the page is kept across a rotation by what it saves`() {
        assertTrue("var questions by rememberSaveable(saver = QuestionsSaver) {" in code)
        val saver = code.substringAfter("private val QuestionsSaver = ").substringBefore("\n)\n")
        assertTrue(saver, "save = { it.value.saved() }" in saver)
        assertTrue(saver, "SetupQuestions.ofSaved(saved, lastFmQuestionAskable())" in saver)
    }

    @Test
    fun `the page is held by its foot, and the two before it by their heads as they were`() {
        // Each answer puts a row above the card. Held by its head, a page grown past its room
        // would leave the next question, with its two answers, under the fold.
        val last = code.substringAfter("else -> {").substringBefore("SetupNavRow(")
        assertTrue("fromFoot = true" in last)
        assertEquals(2, Regex("""\.verticalScroll\(scroll, reverseScrolling = fromFoot\)""").findAll(code).count())
        val others = code.substringAfter("PAGE_WELCOME -> SetupPage(").substringBefore("else -> {")
        assertFalse("fromFoot" in others)
    }

    @Test
    fun `a screen reader is told what a button answers, and that a row changes its answer`() {
        val card = code.substringAfter("private fun SetupQuestionCard(").substringBefore("\n}\n")
        assertTrue("modifier = Modifier.semantics { heading() }" in card)
        for (answer in listOf("no", "yes")) {
            assertTrue(answer, "spoken = stringResource(R.string.setup_answer_spoken, $answer, question)" in card)
        }
        val button = code.substringAfter("private fun SetupAnswerButton(").substringBefore("\n}\n")
        assertTrue(".semantics { contentDescription = spoken }" in button)
        // A row's words and its answer are read as they are written, and then what a tap does.
        val row = code.substringAfter("private fun SetupAnswerRow(").substringBefore("\n}\n")
        assertTrue("onClickLabel = stringResource(R.string.setup_change_answer)" in row)
        assertTrue("role = Role.Button" in row)
    }

    @Test
    fun `the two answers share the width, and no text of them is cut`() {
        val card = code.substringAfter("private fun SetupQuestionCard(").substringBefore("\n}\n")
        // Half each, whatever their words: the longer word does not take room from the other.
        assertEquals(2, Regex("""Modifier\.weight\(1f\)""").findAll(card).count())
        // One line that gets smaller before it would be cut.
        val button = code.substringAfter("private fun SetupAnswerButton(").substringBefore("\n}\n")
        assertTrue("SetupOneLine(" in button)
        val line = code.substringAfter("private fun SetupOneLine(").substringBefore("\n}\n")
        assertTrue("maxLines = 1" in line)
        assertTrue("TextAutoSize.StepBased(" in line)
    }
}
