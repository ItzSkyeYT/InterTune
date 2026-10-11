/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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
 * The catch-up screen: the questions an install never answered, put one at a time as setup's
 * last page puts them (SetupQuestionsTest), with the answers it did give as rows from the start.
 *
 * It was the old wizard's five cards in a column, each turning into a switch as it was answered
 * and writing its answer at once: a list of toggles to get past (11 Oct 2026). What the screen
 * asks, when its Done can be tapped and what Done stores are held here without a screen, over
 * every store an install can come with.
 */
class OptInCatchUpTest {

    private val both = listOf(false, true)
    private val marks = listOf(null, false, true)

    /** How Last.fm's question can stand in a store: never asked, answered by the setting, or by the old switch. */
    private val lastFmStates: List<(MutablePreferences) -> Unit> = listOf(
        { },
        { it[SimilarSourceKey] = SimilarSource.YOUTUBE.name },
        { it[SimilarSourceKey] = SimilarSource.LASTFM.name },
        { it[SimilarSourceKey] = SimilarSource.BOTH.name },
        { it[SimilarFromLastFmKey] = false },
        { it[SimilarFromLastFmKey] = true },
    )

    /** A store with these answers in it. Null is a question this install was never asked. */
    private fun store(
        updates: Boolean?,
        questions: Boolean?,
        news: Boolean?,
        count: Boolean?,
        lastFm: (MutablePreferences) -> Unit = { },
    ) = mutablePreferencesOf().also { prefs ->
        updates?.let { prefs[UpdateCheckEnabledKey] = it }
        questions?.let { prefs[PollsEnabledKey] = it }
        news?.let { prefs[AnnouncementsEnabledKey] = it }
        count?.let { prefs[UsageCountEnabledKey] = it }
        lastFm(prefs)
    }

    /** Every store an install can come with: each of the four never asked, off or on, and Last.fm in its six states. */
    private val stores: List<Preferences> = buildList {
        for (updates in marks) for (questions in marks) for (news in marks) for (count in marks) for (lastFm in lastFmStates) {
            add(store(updates, questions, news, count, lastFm))
        }
    }

    /** Whether the catch-up screen would open over this store. */
    private fun owed(prefs: Preferences, lastFmAskable: Boolean) = catchUpOwed(
        updates = prefs[UpdateCheckEnabledKey],
        questions = prefs[PollsEnabledKey],
        news = prefs[AnnouncementsEnabledKey],
        count = prefs[UsageCountEnabledKey],
        lastFmOwed = lastFmAskable && !SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey]),
    )

    /** How similar songs are found as a store has it. */
    private fun similar(prefs: Preferences) = SimilarSources.stored(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])

    /** The questions of a page that have no answer yet, in the order they are put. */
    private fun open(page: SetupQuestions) = page.asked.filter { it !in page.answers }

    /** The page with every question that is still open answered in turn. */
    private fun answered(page: SetupQuestions, yes: (SetupQuestion) -> Boolean): SetupQuestions {
        var now = page
        while (true) {
            val question = now.open ?: return now
            now = now.answer(question, yes(question))
        }
    }

    /** What is stored once Done has been tapped on [page], opened over [prefs], which stay as they are. */
    private fun afterDone(page: SetupQuestions, prefs: Preferences): MutablePreferences {
        val after = prefs.toMutablePreferences()
        catchUpChoices(page, prefs)!!.store(after)
        return after
    }

    // What is asked

    @Test
    fun `the screen is owed exactly when it has a question to put`() {
        for (askable in both) for (prefs in stores) {
            val page = catchUpQuestions(prefs, askable)
            val at = "$prefs, Last.fm askable $askable"
            assertEquals(at, owed(prefs, askable), page.open != null)
            assertEquals(at, owed(prefs, askable), !page.allAnswered)
        }
    }

    @Test
    fun `an install that was never asked anything is put setup's questions in setup's order`() {
        val page = catchUpQuestions(mutablePreferencesOf(), lastFmAskable = true)
        assertEquals(listOf(UPDATES, WORD, COUNT, LASTFM), page.asked)
        assertEquals(emptyMap<SetupQuestion, Boolean>(), page.answers)
        assertEquals(UPDATES, page.open)
        // Last.fm's only where the build can ask it.
        assertEquals(listOf(UPDATES, WORD, COUNT), catchUpQuestions(mutablePreferencesOf(), lastFmAskable = false).asked)
    }

    @Test
    fun `an answer that is stored is a row from the start, and the card is on the first question that never had one`() {
        for (askable in both) for (prefs in stores) {
            val before = prefs.asMap().toMap()
            val page = catchUpQuestions(prefs, askable)
            val at = "$prefs, Last.fm askable $askable"

            assertEquals(at, prefs[UpdateCheckEnabledKey], page.answers[UPDATES])
            assertEquals(at, prefs[UsageCountEnabledKey], page.answers[COUNT])
            // A choice made through the setting or the old switch is an answer too.
            val lastFm = if (askable && SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])) {
                similar(prefs) != SimilarSource.YOUTUBE
            } else {
                null
            }
            assertEquals(at, lastFm, page.answers[LASTFM])

            // Setup's order, with Last.fm's question only where the build can ask it.
            assertEquals(at, listOf(UPDATES, WORD, COUNT, LASTFM).filter { it in page.asked }, page.asked)
            assertTrue(at, UPDATES in page.asked && COUNT in page.asked)
            assertEquals(at, askable, LASTFM in page.asked)
            assertTrue(at, page.answers.keys.all { it in page.asked })
            assertEquals(at, open(page).firstOrNull(), page.open)

            // Opening the screen writes nothing.
            assertEquals(at, before, prefs.asMap())
        }
    }

    // News and questions, which are one question here

    @Test
    fun `a word from InterTune is put to whoever never answered for questions, or said no and was never asked about news`() {
        val never = listOf<Pair<Boolean?, Boolean?>>(null to null, null to false, null to true, false to null)
        for ((questions, news) in never) for (count in marks) {
            val prefs = store(updates = true, questions = questions, news = news, count = count)
            val page = catchUpQuestions(prefs, lastFmAskable = false)
            val at = "questions $questions, news $news, count $count"
            assertTrue(at, owed(prefs, lastFmAskable = false))
            assertTrue(at, WORD in page.asked)
            assertFalse(at, WORD in page.answers)
            assertEquals(at, WORD, page.open)

            // And the one answer is the answer to both, as in setup.
            for (yes in both) {
                val after = afterDone(answered(page) { yes }, prefs)
                assertEquals(at, yes, after[PollsEnabledKey])
                assertEquals(at, yes, after[AnnouncementsEnabledKey])
            }
        }
    }

    @Test
    fun `news and questions answered alike are one row with that answer`() {
        for (word in both) for (count in marks) {
            val page = catchUpQuestions(store(updates = null, questions = word, news = word, count = count), lastFmAskable = false)
            assertEquals(word, page.answers[WORD])
            assertEquals(UPDATES, page.open)
        }
    }

    @Test
    fun `an install that said yes to questions and was never asked about news is not asked, and has a row that says yes`() {
        // The checker gives it news and writes that yes down (PollChecker.adoptNewsChoice), so
        // nothing is owed for the two.
        assertFalse(owed(store(updates = false, questions = true, news = null, count = false), lastFmAskable = false))

        // Asked something else, it has its row, and the card is on the other question.
        val prefs = store(updates = false, questions = true, news = null, count = null)
        val page = catchUpQuestions(prefs, lastFmAskable = false)
        assertEquals(true, page.answers[WORD])
        assertEquals(COUNT, page.open)
        // The checker may write its yes down while the screen is up: the screen is the same over either store.
        val adopted = prefs.toMutablePreferences().also { it[AnnouncementsEnabledKey] = true }
        assertEquals(page, catchUpQuestions(adopted, lastFmAskable = false))

        // Done writes the yes to news down, as the checker would have.
        val after = afterDone(page.answer(COUNT, yes = false), prefs)
        assertEquals(true, after[PollsEnabledKey])
        assertEquals(true, after[AnnouncementsEnabledKey])

        // The row can be changed like any other, and a no is then a no to both.
        val declined = afterDone(page.answer(COUNT, yes = false).reopen(WORD).answer(WORD, yes = false), prefs)
        assertEquals(false, declined[PollsEnabledKey])
        assertEquals(false, declined[AnnouncementsEnabledKey])

        // One that said no to questions was never given news by the checker, and is asked.
        val no = catchUpQuestions(store(updates = false, questions = false, news = null, count = false), lastFmAskable = false)
        assertFalse(WORD in no.answers)
        assertEquals(WORD, no.open)
    }

    @Test
    fun `news and questions set apart in Settings are not put, and Done leaves both as they are`() {
        for ((questions, news) in listOf(true to false, false to true)) {
            // Both have their answer, so by themselves they open nothing.
            assertFalse(owed(store(updates = true, questions = questions, news = news, count = true), lastFmAskable = false))

            for (askable in both) {
                val prefs = store(updates = true, questions = questions, news = news, count = null)
                val page = catchUpQuestions(prefs, askable)
                // No row could say what the two are, and no single answer may replace them.
                assertFalse(WORD in page.asked)
                assertFalse(WORD in page.answers)
                assertEquals(COUNT, page.open)

                for (yes in both) {
                    val done = answered(page) { yes }
                    assertTrue(done.allAnswered)
                    val after = afterDone(done, prefs)
                    assertEquals(questions, after[PollsEnabledKey])
                    assertEquals(news, after[AnnouncementsEnabledKey])
                    assertEquals(yes, after[UsageCountEnabledKey])
                    assertFalse(owed(after, askable))
                }
            }
        }
    }

    // Done

    @Test
    fun `Done waits for an answer to every question put`() {
        for (askable in both) for (prefs in stores) {
            var page = catchUpQuestions(prefs, askable)
            val at = "$prefs, Last.fm askable $askable"
            while (true) {
                val question = page.open ?: break
                assertFalse(at, page.allAnswered)
                // An earlier answer looked at again does not stand in for the one that is missing.
                for (row in page.answers.keys) assertFalse(at, page.reopen(row).allAnswered)
                page = page.answer(question, yes = true)
            }
            assertTrue(at, page.allAnswered)
            // A row tapped to be looked at again takes nothing back: its answer stands until another is given.
            for (row in page.asked) {
                assertEquals(at, row, page.reopen(row).open)
                assertTrue(at, page.reopen(row).allAnswered)
                assertTrue(at, page.reopen(row).answer(row, yes = false).allAnswered)
            }
        }
    }

    @Test
    fun `after Done nothing is owed, whatever was stored and whatever is answered`() {
        for (askable in both) for (prefs in stores) {
            val page = catchUpQuestions(prefs, askable)
            val asking = open(page)
            for (given in 0 until (1 shl asking.size)) {
                val done = answered(page) { given shr asking.indexOf(it) and 1 == 1 }
                val at = "$prefs, Last.fm askable $askable, answered ${done.answers}"
                assertTrue(at, done.allAnswered)

                val after = afterDone(done, prefs)
                // Or the screen would come straight back.
                assertFalse(at, owed(after, askable))
                // Opened all the same, it would have the same rows and nothing to ask.
                val again = catchUpQuestions(after, askable)
                assertNull(at, again.open)
                assertEquals(at, done.asked, again.asked)
                assertEquals(at, done.answers, again.answers)
            }
        }
    }

    @Test
    fun `each answer is stored as it stands when Done is tapped, given here or before and changed here or not`() {
        for (askable in both) for (prefs in stores) for (changed in both) {
            var page = catchUpQuestions(prefs, askable)
            // Every row tapped and given the other answer, or every row left alone.
            if (changed) for ((row, yes) in page.answers.toMap()) page = page.reopen(row).answer(row, !yes)
            page = answered(page) { it == UPDATES || it == COUNT }
            val at = "$prefs, Last.fm askable $askable, rows changed $changed"

            val after = afterDone(page, prefs)
            assertEquals(at, page.answers[UPDATES], after[UpdateCheckEnabledKey])
            assertEquals(at, page.answers[COUNT], after[UsageCountEnabledKey])
            if (WORD in page.asked) {
                assertEquals(at, page.answers[WORD], after[PollsEnabledKey])
                assertEquals(at, page.answers[WORD], after[AnnouncementsEnabledKey])
            }
            if (askable) {
                assertEquals(at, page.answers[LASTFM], similar(after) != SimilarSource.YOUTUBE)
            }
        }
    }

    @Test
    fun `a row nobody touched is stored as it was`() {
        for (askable in both) for (prefs in stores) {
            val after = afterDone(answered(catchUpQuestions(prefs, askable)) { false }, prefs)
            val at = "$prefs, Last.fm askable $askable"
            prefs[UpdateCheckEnabledKey]?.let { assertEquals(at, it, after[UpdateCheckEnabledKey]) }
            prefs[UsageCountEnabledKey]?.let { assertEquals(at, it, after[UsageCountEnabledKey]) }
            // News and questions where both had an answer, the same one or not.
            if (prefs[PollsEnabledKey] != null && prefs[AnnouncementsEnabledKey] != null) {
                assertEquals(at, prefs[PollsEnabledKey], after[PollsEnabledKey])
                assertEquals(at, prefs[AnnouncementsEnabledKey], after[AnnouncementsEnabledKey])
            }
            // Last.fm alone must not come back as Both because a row can only say yes, nor the
            // old switch lose its meaning.
            if (askable && SimilarSources.asked(prefs[SimilarSourceKey], prefs[SimilarFromLastFmKey])) {
                assertEquals(at, similar(prefs), similar(after))
            }
        }
    }

    @Test
    fun `Done stores the answers and nothing else, and nothing for a question the build cannot put`() {
        val answers = setOf(UpdateCheckEnabledKey, PollsEnabledKey, AnnouncementsEnabledKey, UsageCountEnabledKey)
        for (askable in both) for (prefs in stores) {
            val after = afterDone(answered(catchUpQuestions(prefs, askable)) { true }, prefs)
            val at = "$prefs, Last.fm askable $askable"
            val expected = prefs.asMap().keys + answers + if (askable) setOf(SimilarSourceKey) else emptySet()
            assertEquals(at, expected, after.asMap().keys)
            if (!askable) {
                // So a later build that can ask still owes the question, and asks it.
                assertEquals(at, prefs[SimilarSourceKey], after[SimilarSourceKey])
                assertEquals(at, prefs[SimilarFromLastFmKey], after[SimilarFromLastFmKey])
            }
        }
    }

    // The preview, from Developer

    @Test
    fun `the preview opens with nothing answered, whatever is stored, and its Done stores nothing`() {
        for (askable in both) for (prefs in stores) {
            val before = prefs.asMap().toMap()
            val page = catchUpQuestions(prefs, askable, preview = true)
            val at = "$prefs, Last.fm askable $askable"
            // The screen of an install that was never asked anything, every question of the build on it.
            assertEquals(at, catchUpQuestions(mutablePreferencesOf(), askable), page)
            assertEquals(at, emptyMap<SetupQuestion, Boolean>(), page.answers)
            assertEquals(at, UPDATES, page.open)
            assertEquals(at, if (askable) 4 else 3, page.asked.size)

            // Answered in every way, there is nothing for its Done to store.
            for (given in 0 until (1 shl page.asked.size)) {
                val done = answered(page) { given shr page.asked.indexOf(it) and 1 == 1 }
                assertTrue(at, done.allAnswered)
                assertNull(at, catchUpChoices(done, prefs, preview = true))
            }
            assertEquals(at, before, prefs.asMap())
        }
    }

    // A rotation

    @Test
    fun `the screen comes back from a rotation as it stood`() {
        for (askable in both) for (prefs in stores) {
            var page = catchUpQuestions(prefs, askable)
            val asked = page.asked
            // As it opens, after each answer, and with each row tapped to be changed.
            val stood = mutableListOf(page)
            while (true) {
                val question = page.open ?: break
                stood += page.answers.keys.map { page.reopen(it) }
                page = page.answer(question, yes = question != WORD)
                stood += page
            }
            stood += page.asked.map { page.reopen(it) }
            for (was in stood) {
                assertEquals("$prefs, Last.fm askable $askable, ${was.saved()}", was, SetupQuestions.ofSaved(was.saved(), asked))
            }
        }
    }

    @Test
    fun `anything else that comes back is not this screen, and it starts from what is stored`() {
        // Three questions, news and questions being set apart, and all four.
        val three = catchUpQuestions(store(updates = true, questions = true, news = false, count = null), lastFmAskable = true)
        val four = catchUpQuestions(store(updates = true, questions = null, news = null, count = null), lastFmAskable = true)
        assertEquals(listOf(UPDATES, COUNT, LASTFM), three.asked)
        assertNull(SetupQuestions.ofSaved(four.saved(), three.asked))
        assertNull(SetupQuestions.ofSaved(three.saved(), four.asked))
        for (saved in listOf("", "1", "1--", "1x-1", "1--3", "1--x", "----")) {
            assertNull(saved, SetupQuestions.ofSaved(saved, three.asked))
        }
        // What setup's own page saves is read as it was.
        for (askable in both) {
            val setup = SetupQuestions.from(mutablePreferencesOf(), askable).answer(UPDATES, yes = true)
            assertEquals(SetupQuestions.ofSaved(setup.saved(), askable), SetupQuestions.ofSaved(setup.saved(), setup.asked))
        }
    }

    // What follows reads the source: it is a screen, and no test here can tap it.

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** A file's text without its comments, so a sentence that names a thing is not taken for the thing. */
    private fun code(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private val screen = code("ui/screens/OptInCatchUp.kt")

    @Test
    fun `the questions are drawn by what draws setup's, and no card or switch of the old screen is left`() {
        // One drawing for the two: the card, its two answers, the rows and the line that closes.
        assertEquals(1, Regex("""\bSetupQuestionsBody\(""").findAll(screen).count())
        val setup = code("ui/screens/ShortSetup.kt")
        assertEquals(1, Regex("""\bSetupQuestionsBody\(""").findAll(setup.substringBefore("internal fun SetupQuestionsBody(")).count())
        assertTrue("internal fun SetupQuestionsBody(" in setup)

        for (card in listOf("UpdateOptInCard", "PollsOptInCard", "NewsOptInCard", "UsageCountOptInCard", "LastFmSimilarOptInCard")) {
            assertFalse(card, card in screen)
        }
        assertFalse("SwitchPreference" in screen)
        assertFalse(Regex("""\bSwitch\(""").containsMatchIn(screen))
    }

    @Test
    fun `the screen opens on what catchUpQuestions makes of the store, read once`() {
        assertTrue("val opening = remember { catchUpQuestions(stored, lastFmQuestionAskable(), preview) }" in screen)
        // Not followed as it changes: an answer written by something else would move a row under a finger.
        assertFalse(Regex("""remember\w*Preference""").containsMatchIn(screen))
    }

    @Test
    fun `nothing is written before Done, and nothing at all from a preview`() {
        // One write in the whole screen: what catchUpChoices gives, and none where it gives nothing.
        assertEquals(1, Regex("""dataStore\.edit\b""").findAll(screen).count())
        assertEquals(1, Regex("""\.store\(""").findAll(screen).count())
        val done = screen.substringAfter("val done: () -> Unit = {").substringBefore("Dialog(")
        assertTrue(done, "val answers = catchUpChoices(questions, stored, preview)" in done)
        assertTrue(done, Regex("""if \(answers != null\) \{\s*context\.dataStore\.edit \{ answers\.store\(it\) }""").containsMatchIn(done))
        // The screen is closed after the write, and by a preview as well.
        assertEquals(1, Regex("""\bonDone\(\)""").findAll(done).count())
    }

    @Test
    fun `Done is dead until every question has its answer, and is not part of what scrolls`() {
        val done = screen.substringAfter("val done: () -> Unit = {").substringBefore("Dialog(")
        assertTrue(done, "if (questions.allAnswered && !finishing) {" in done)
        val button = screen.substringAfter("Button(").substringBefore("Text(stringResource(R.string.catch_up_done))")
        assertTrue(button, "enabled = questions.allAnswered" in button)
        assertTrue(button, "onClick = done" in button)

        // What scrolls has the room the button leaves, is held by its foot as setup's page of
        // questions is, and ends before the button.
        assertEquals(1, Regex("""\.verticalScroll\(scroll, reverseScrolling = true\)""").findAll(screen).count())
        val scrolled = screen.substringAfter("Surface(").substringBefore("Button(")
        assertTrue(scrolled, Regex("""\.weight\(1f\)[^}]*\.verticalScroll\(scroll, reverseScrolling = true\)""").containsMatchIn(scrolled))
        assertTrue(scrolled, "SetupQuestionsBody(" in scrolled.substringAfter(".verticalScroll("))
        // The two are side by side in one column: the button is not inside what scrolls.
        val lines = screen.lines()
        val column = lines.take(lines.indexOfFirst { ".verticalScroll(" in it }).last { it.trim() == "Column(" }
        val under = lines.first { it.trim() == "Button(" }
        assertEquals(column.takeWhile { it == ' ' }.length, under.takeWhile { it == ' ' }.length)
    }

    @Test
    fun `the screen is left by answering it, not by Back or a tap beside it`() {
        assertTrue("onDismissRequest = { }" in screen)
        assertTrue("dismissOnBackPress = false" in screen)
        assertTrue("dismissOnClickOutside = false" in screen)
    }

    @Test
    fun `the screen is kept across a rotation by what it saves`() {
        assertTrue("var questions by rememberSaveable(saver = remember { catchUpSaver(opening.asked) }) {" in screen)
        val saver = screen.substringAfter("private fun catchUpSaver(").substringBefore("\n)\n")
        assertTrue(saver, "save = { it.value.saved() }" in saver)
        assertTrue(saver, "SetupQuestions.ofSaved(saved, asked)" in saver)
    }

    @Test
    fun `every text of the screen is in English and in French, and the closing line names the button`() {
        val used = Regex("""R\.string\.([a-z0-9_]+)""").findAll(screen).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("catch_up_title", "catch_up_body", "catch_up_done", "catch_up_all_answered"), used)
        for (file in listOf("values/strings-ot.xml", "values-fr/strings-ot.xml")) {
            val texts = File("src/main/res/$file").readText()
            for (name in used) assertTrue("$name is not in $file", "<string name=\"$name\">" in texts)
            // Setup's line says to tap the tick, and this screen has none: its own names its
            // button, by whatever the button says.
            val closing = texts.substringAfter("<string name=\"catch_up_all_answered\">").substringBefore("</string>")
            assertTrue(closing, "%1\$s" in closing)
            assertFalse(closing, "'" in closing.replace("\\'", ""))
        }
        assertTrue("closing = stringResource(R.string.catch_up_all_answered, stringResource(R.string.catch_up_done))" in screen)
    }

    @Test
    fun `Developer shows the screen as a preview, and the app opens it for real`() {
        val developer = code("ui/screens/settings/fragments/DeveloperFrag.kt")
        assertTrue("Text(\"Show the catch-up questions\")" in developer)
        assertEquals(1, Regex("""\bOptInCatchUp\(""").findAll(developer).count())
        val shown = developer.substringAfter("OptInCatchUp(").substringBefore("\n        }")
        assertTrue(shown, "preview = true" in shown)
        // Its Done closes it, and the row touches nothing that is stored.
        val row = developer.substringAfter("var catchUpPreview").substringBefore("Spacer(")
        assertTrue(row, Regex("""onDone = \{ catchUpPreview = false }""").containsMatchIn(row))
        assertFalse(row, "dataStore" in row)

        val activity = code("MainActivity.kt")
        val real = activity.substringAfter("if (catchUpOpen) {").substringBefore("activeCount.ping()")
        assertTrue(real, "OptInCatchUp(" in real)
        assertFalse(real, "preview" in real)
    }
}
