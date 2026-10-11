/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsStartWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.dd3boh.outertune.LocalPollChecker
import com.dd3boh.outertune.LocalUpdateChecker
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AnnouncementsEnabledKey
import com.dd3boh.outertune.constants.PollsEnabledKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.lastFmQuestionAskable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The questions somebody was never asked, asked.
 *
 * Setup is the only place that raises the update, questions and counting opt ins, and it runs
 * once, which leaves two ways to hold an install whose answers were never given:
 *
 *  - updating from a build whose onboarding predates the question, so setup ran before there was
 *    anything to answer
 *  - restoring a backup taken before the answer was stored, since an absent key restores as absent
 *
 * Both preferences read as off in that state. That is the right default but a silent one: no update
 * check ever runs, no question is ever fetched, and nothing says so. The first poll went out to an
 * install base where almost nobody had been asked, so almost nobody answered.
 *
 * A slice of setup rather than a second copy of it: the questions of its last page, put the same
 * way and drawn by the same code (SetupQuestionsBody), with none of the setup around them. One
 * question at a time in a card, with a yes and a no under it. An answer this install has already
 * given is a row above the card from the start, which keeps the screen honest about how little
 * it is actually asking for, and a tap on a row puts its question back in the card. It was the
 * old wizard's five cards in a column, each of them a switch once answered, and a column of
 * switches reads as something to get past.
 *
 * Nothing is stored while the questions are answered. Done stores every answer in one edit, as
 * setup's tick does ([catchUpChoices]), and then starts what a yes starts.
 *
 * Not dismissable, for the reason the update dialog before it was not: a prompt that can be waved
 * away without answering leaves the preference unset, and then it is owed all over again on the next
 * launch. Done only unlocks once every question put has its answer, so the screen is left by
 * answering it.
 */
@Composable
fun OptInCatchUp(
    /** Outlives this screen, for the checks that a yes starts as it closes. */
    appScope: CoroutineScope,
    /**
     * The screen for a look, from Developer: it stands as it would for an install that was never
     * asked anything, and its Done closes it and stores nothing.
     */
    preview: Boolean = false,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = LocalUpdateChecker.current
    val pollChecker = LocalPollChecker.current

    // What is stored, read once and waited for, as rememberPreference reads: the screen has to
    // stand as it will stay from its first frame. A rotation reads it again and finds the same
    // questions: the one answer written while the screen is up is the checker's yes to news for
    // whoever said yes to questions, and the screen is the same with it or without.
    val stored = remember { runBlocking(Dispatchers.IO) { context.dataStore.data.first() } }
    val opening = remember { catchUpQuestions(stored, lastFmQuestionAskable(), preview) }
    // The questions and the answers given to them. Only Done writes an answer down.
    var questions by rememberSaveable(saver = remember { catchUpSaver(opening.asked) }) {
        mutableStateOf(opening)
    }
    val scroll = rememberScrollState()

    // Once only, and the answers are written before the screen is closed: what comes after it
    // reads them, the day's count first (MainActivity).
    var finishing by remember { mutableStateOf(false) }
    val done: () -> Unit = {
        if (questions.allAnswered && !finishing) {
            finishing = true
            val answers = catchUpChoices(questions, stored, preview)
            coroutineScope.launch {
                // A preview has nothing to store, and only closes.
                if (answers != null) {
                    context.dataStore.edit { answers.store(it) }
                    // What a yes starts, as from setup's tick: a look for a release now and not
                    // hours on, and the first question or announcement fetched.
                    if (answers.updates) appScope.launch { updateChecker.check(force = true) }
                    appScope.launch { pollChecker.check(force = answers.questions || answers.news) }
                }
                onDone()
            }
        }
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            // Laid out over the whole screen, the bars with it. Left to fit inside them, the
            // window stopped under the status bar and above the navigation bar, and the screen
            // behind showed through both, dimmed: Home's top row behind the clock, on a page
            // that is meant to be the only thing there.
            decorFitsSystemWindows = false,
        )
    ) {
        val lightPage = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        BarsReadOn(lightPage)
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        // The page's colour runs under the bars. What is on the page keeps clear
                        // of them, and of a cut-out when the phone is on its side.
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    // What scrolls, in the room the button leaves. Held by its foot, as setup's page
                    // of questions is and for its reason: it grows by a row with each answer, and
                    // the card with its two answers is the last thing on it.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .moreBelow(scroll, MaterialTheme.colorScheme.surface, fromFoot = true)
                            .verticalScroll(scroll, reverseScrolling = true)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(80.dp)
                                .padding(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.catch_up_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.catch_up_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.height(8.dp))

                        SetupQuestionsBody(
                            questions = questions,
                            onChange = { questions = it },
                            // Not while Done is storing the answers.
                            live = !finishing,
                            scroll = scroll,
                            closing = stringResource(R.string.catch_up_all_answered, stringResource(R.string.catch_up_done)),
                        )
                    }

                    // Under what scrolls and not part of it, so it is in view whatever the size of
                    // the screen or of the type.
                    Button(
                        enabled = questions.allAnswered,
                        onClick = done,
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(stringResource(R.string.catch_up_done))
                    }
                }
                // Before Android 8 the navigation bar's buttons are white whatever is under them,
                // and on a light page they would be three shapes nobody can see.
                if (lightPage && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) NavigationBarShade()
            }
        }
    }
}

/**
 * In a dialog laid out over the whole screen: the bars' icons are drawn to be read on its page,
 * dark on a light page and light on a dark one. A dialog's window starts with light icons
 * whatever is under them, which on a light page is a clock nobody can see.
 */
@Composable
private fun BarsReadOn(lightPage: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightPage
            isAppearanceLightNavigationBars = lightPage
        }
    }
}

/** What the system itself lays under a bar whose icons it cannot darken. */
private val BarShade = Color(0x801B1B1B)

/** That shade under the navigation bar, on whichever edge it stands: the foot, or a side with the phone turned. */
@Composable
private fun BoxScope.NavigationBarShade() {
    val bar = WindowInsets.navigationBars
    Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(bar).background(BarShade))
    Spacer(Modifier.align(Alignment.CenterStart).fillMaxHeight().windowInsetsStartWidth(bar).background(BarShade))
    Spacer(Modifier.align(Alignment.CenterEnd).fillMaxHeight().windowInsetsEndWidth(bar).background(BarShade))
}

/**
 * Whether the catch-up screen is owed: there is a question this install has never answered. An
 * unset preference is "never asked", since every way of answering writes one, the setup pages
 * included (SetupChoices), so nobody who declined is asked again.
 *
 * News is owed only by those who did not say yes to questions: the rest get it from the checker,
 * which writes their yes down (PollChecker.adoptNewsChoice). [lastFmOwed] is worked out by the
 * caller, because that question is only owed where it can be put (lastFmQuestionAskable).
 */
internal fun catchUpOwed(updates: Boolean?, questions: Boolean?, news: Boolean?, count: Boolean?, lastFmOwed: Boolean): Boolean =
    updates == null || questions == null || count == null || lastFmOwed || (news == null && questions != true)

/**
 * The screen as it opens over what is [stored]: setup's questions in setup's order, a row for
 * each answer this install has given, and in the card the first question it never answered.
 * There is one exactly when the screen is owed ([catchUpOwed]).
 *
 * News and questions are one question here as they are in setup, a word from InterTune, and
 * they are read as setup reads them (SetupQuestions.from) but for two installs:
 *
 *  - One that said yes to questions and was never asked about news is not asked. The checker
 *    gives it news and writes that yes down (PollChecker.adoptNewsChoice), so it has its row,
 *    and the row says yes.
 *  - One that has an answer to each, and not the same one, set them apart in Settings. A row
 *    could not say what the two are and one answer must not replace them, so the question is
 *    not put, and Done leaves both as they are.
 *
 * @param preview the screen for a look, from Developer: nothing answered, whatever is stored
 */
internal fun catchUpQuestions(stored: Preferences, lastFmAskable: Boolean, preview: Boolean = false): SetupQuestions {
    if (preview) return SetupQuestions.from(emptyPreferences(), lastFmAskable)
    val page = SetupQuestions.from(stored, lastFmAskable)
    val questions = stored[PollsEnabledKey]
    val news = stored[AnnouncementsEnabledKey]
    return when {
        questions == true && news == null -> page.answer(SetupQuestion.WORD, yes = true)
        questions != null && news != null && questions != news -> {
            val asked = page.asked - SetupQuestion.WORD
            page.copy(asked = asked, open = asked.firstOrNull { it !in page.answers })
        }
        else -> page
    }
}

/**
 * What Done stores: each answer as setup's tick stores it (SetupQuestions.choices), the ones
 * given before among them, which are stored as they were unless their row was changed. News and
 * questions that were not put ([catchUpQuestions]) are stored as they stand in [stored].
 *
 * Done is only there to be tapped once every question put has its answer
 * (SetupQuestions.allAnswered), so nothing is a no here for want of an answer.
 *
 * @return null from the [preview], which stores nothing
 */
internal fun catchUpChoices(page: SetupQuestions, stored: Preferences, preview: Boolean = false): SetupChoices? {
    if (preview) return null
    val answers = page.choices()
    return if (SetupQuestion.WORD in page.asked) answers
    else answers.copy(questions = stored[PollsEnabledKey] ?: false, news = stored[AnnouncementsEnabledKey] ?: false)
}

/**
 * The screen across a rotation, or a stay in the background: the answers given and the question
 * in the card (SetupQuestions.saved), for the questions it was opened with ([asked]). Anything
 * else that comes back restores nothing, and the screen starts from what is stored.
 */
private fun catchUpSaver(asked: List<SetupQuestion>) = Saver<MutableState<SetupQuestions>, String>(
    save = { it.value.saved() },
    restore = { saved -> SetupQuestions.ofSaved(saved, asked)?.let { mutableStateOf(it) } },
)
