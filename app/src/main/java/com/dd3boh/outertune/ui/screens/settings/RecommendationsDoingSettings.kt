/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.LearnFromListeningKey
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.constants.ShadowComparisonKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.EngineWeight
import com.dd3boh.outertune.engine.BrierVerdict
import com.dd3boh.outertune.engine.Calibration
import com.dd3boh.outertune.engine.DoingSummary
import com.dd3boh.outertune.engine.ENGINE_TEAM
import com.dd3boh.outertune.engine.EndLabel
import com.dd3boh.outertune.engine.Features
import com.dd3boh.outertune.engine.ListenDay
import com.dd3boh.outertune.engine.Trend
import com.dd3boh.outertune.engine.HeldRow
import com.dd3boh.outertune.engine.NotSourceText
import com.dd3boh.outertune.engine.PredictionLine
import com.dd3boh.outertune.engine.ShadowLine
import com.dd3boh.outertune.engine.brierFooter
import com.dd3boh.outertune.engine.engineRowsHeld
import com.dd3boh.outertune.engine.notSourceText
import com.dd3boh.outertune.engine.predictionLine
import com.dd3boh.outertune.engine.shadowLine
import com.dd3boh.outertune.engine.cardsByTeam
import com.dd3boh.outertune.engine.cardsSeen
import com.dd3boh.outertune.engine.clockLength
import com.dd3boh.outertune.engine.doingSummary
import com.dd3boh.outertune.engine.endCounts
import com.dd3boh.outertune.engine.endLabel
import com.dd3boh.outertune.engine.engineShowing
import com.dd3boh.outertune.engine.hasNumbers
import com.dd3boh.outertune.engine.heldLines
import com.dd3boh.outertune.engine.listenDay
import com.dd3boh.outertune.engine.oneInWords
import com.dd3boh.outertune.engine.onlyWaiting
import com.dd3boh.outertune.engine.originCounts
import com.dd3boh.outertune.engine.per100Text
import com.dd3boh.outertune.engine.percentText
import com.dd3boh.outertune.engine.per100Texts
import com.dd3boh.outertune.engine.predictionBands
import com.dd3boh.outertune.engine.predictionOf
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ExplainButton
import com.dd3boh.outertune.ui.component.ExplainedGroupTitle
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.DISCOVER_TEAM
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * How the recommendations are doing, for anyone curious, not only for whoever tunes the engine.
 *
 * It opens with one plain summary: how many of the cards Best recommendations showed were played,
 * and whether that is going up. Every figure below it carries a line saying what it means and
 * whether higher is better, with the full working behind its "i". Then what the engine has to
 * learn from, the last listens, and the weights and developer tuning, set apart at the foot.
 *
 * Every stop the engine learns from is shown here, with the two facts about it that matter most,
 * because a recommendation that cannot explain itself is not one anybody should be asked to trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsDoingSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecommendationsViewModel = hiltViewModel(),
) {
    val listens by viewModel.listens.collectAsState(initial = 0)
    val counted by viewModel.counted.collectAsState(initial = 0)
    val sessions by viewModel.sessions.collectAsState(initial = 0)
    val byEnd by viewModel.byEnd.collectAsState(initial = emptyList())
    val startsByOrigin by viewModel.startsByOrigin.collectAsState(initial = emptyList())
    val cardsSeenRows by viewModel.cardsSeen.collectAsState(initial = emptyList())
    val signals by viewModel.signals.collectAsState(initial = 0)
    val recent by viewModel.recent.collectAsState(initial = emptyList())
    // Null until read, so the summary does not say "nothing yet" for the moment before it knows.
    val gradedByTeam by viewModel.gradedByTeam.collectAsState(initial = null)
    val cardTrend by viewModel.cardTrend.collectAsState(initial = null)
    val calibration by viewModel.calibration.collectAsState(initial = emptyList())
    val weights by viewModel.weights.collectAsState(initial = emptyList())
    val buildScores by viewModel.buildScores.collectAsState(initial = emptyList())
    val (shadowComparison, onShadowComparisonChange) = rememberPreference(ShadowComparisonKey, defaultValue = true)
    val (quickPicksSource, _) = rememberEnumPreference(QuickPicksSourceKey, defaultValue = QuickPicksSource.YOUTUBE)
    // Off, EngineLearning.run returns at once and nothing is judged, which the summary has to say.
    val (learnFromListening, _) = rememberPreference(LearnFromListeningKey, defaultValue = true)
    val locale = Locale.getDefault()
    val updates = weights.maxOfOrNull { it.updates } ?: 0

    // Lower case, like the labels they stand among. Not recorded is for both: a listen from the
    // old play log, which kept neither how it ended nor where it began, and one started a way that
    // does not say where from.
    val notRecorded = stringResource(R.string.recommendations_not_recorded)
    val endLabels = mapOf(
        EndLabel.REACHED_END to stringResource(R.string.recommendations_ended),
        EndLabel.ENDED_EARLY to stringResource(R.string.recommendations_ended_early),
        EndLabel.SKIPPED to stringResource(R.string.recommendations_skipped),
        EndLabel.REPLACED to stringResource(R.string.recommendations_replaced),
        EndLabel.STOPPED to stringResource(R.string.recommendations_stopped),
        EndLabel.FAILED to stringResource(R.string.recommendations_error),
        EndLabel.IN_PROGRESS to stringResource(R.string.recommendations_in_progress),
        EndLabel.NOT_RECORDED to notRecorded,
    )
    val originLabels = mapOf(
        PlayOrigin.UNKNOWN to notRecorded,
        PlayOrigin.SEARCH to stringResource(R.string.recommendations_origin_search),
        PlayOrigin.QUICK_PICKS to stringResource(R.string.recommendations_origin_quick_picks),
        PlayOrigin.HOME_ROW to stringResource(R.string.recommendations_origin_home_row),
        PlayOrigin.PLAYLIST to stringResource(R.string.recommendations_origin_playlist),
        PlayOrigin.ALBUM to stringResource(R.string.recommendations_origin_album),
        PlayOrigin.ARTIST to stringResource(R.string.recommendations_origin_artist),
        PlayOrigin.LIBRARY to stringResource(R.string.recommendations_origin_library),
        PlayOrigin.RADIO to stringResource(R.string.recommendations_origin_radio),
        PlayOrigin.HISTORY to stringResource(R.string.recommendations_origin_history),
        PlayOrigin.STATS to stringResource(R.string.recommendations_origin_stats),
        PlayOrigin.QUEUE to stringResource(R.string.recommendations_origin_queue),
        PlayOrigin.LOCAL_FILES to stringResource(R.string.recommendations_origin_local_files),
        PlayOrigin.MENU to stringResource(R.string.recommendations_origin_menu),
        PlayOrigin.RESUMED to stringResource(R.string.recommendations_origin_resumed),
        PlayOrigin.EXTERNAL to stringResource(R.string.recommendations_origin_external),
        PlayOrigin.RECOGNISED to stringResource(R.string.recommendations_origin_recognised),
        PlayOrigin.WIDGET to stringResource(R.string.recommendations_origin_widget),
        PlayOrigin.DISCOVER to stringResource(R.string.recommendations_origin_discover),
    )
    fun originLabel(origin: PlayOrigin) = originLabels[origin] ?: notRecorded
    val teamNames = mapOf(
        ENGINE_TEAM to stringResource(R.string.recommendations_team_engine),
        2 to stringResource(R.string.recommendations_team_library),
        3 to stringResource(R.string.recommendations_team_youtube),
        DISCOVER_TEAM to stringResource(R.string.discover_something_new),
    )

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        // Held back for 0.11 with the engine: see Unreleased. The ledger below it ships either way.
        val graded = gradedByTeam
        // What each thing counts for: numbers only someone tuning the engine can read, so they sit
        // under For developers, shown when the figures above are.
        var showWeights = false
        if (Unreleased.ENGINE && graded != null) {
            val teams = cardsByTeam(graded)
            val pairs = calibration.map { it.p.toDouble() to it.y.toDouble() }
            val numbers = hasNumbers(teams, buildScores, pairs.size, updates)
            showWeights = numbers

            val waiting = cardsSeenRows.firstOrNull { it.team == ENGINE_TEAM }?.waiting ?: 0
            val held = heldLines(buildScores, quickPicksSource)
            SummaryCard(doingSummary(engineShowing(quickPicksSource), teams, cardTrend, waiting, learnFromListening, engineRowsHeld(held)), locale)
            Spacer(Modifier.height(16.dp))

            // Named for every source, not for Best recommendations: Cards played and Rows that
            // held give a line for each. With no figures the switch stands alone, under no
            // heading, rather than under one that promises figures and has none.
            if (numbers) {
                ExplainedGroupTitle(
                    title = stringResource(R.string.recommendations_figures_title),
                    explanation = stringResource(R.string.recommendations_doing_title_info),
                )
                StatEntry(
                    title = stringResource(R.string.recommendations_wins),
                    explanation = stringResource(R.string.recommendations_wins_info),
                    // In the summary's words, "about 1 in 100", so the same number does not look
                    // like two different ones.
                    numbers = teams.filter { it.cards.seen > 0 }.map { t ->
                        stringResource(
                            R.string.recommendations_wins_line,
                            teamNames[t.team] ?: t.team.toString(), t.cards.played, t.cards.seen, per100Text(t.cards.per100, locale),
                        )
                    }.joinToString("\n").ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                    meaning = stringResource(R.string.recommendations_wins_meaning),
                )
            }
            // A switch, not a figure, so it stays when there is nothing to count: with another
            // source showing, it is what lets the engine be judged at all. With Best
            // recommendations already in the row it builds nothing, and its line says so. With
            // Learn from listening off its rows wait unchecked, so the line does not promise a
            // day later.
            ExplainedSwitchPreference(
                title = stringResource(R.string.shadow_comparison),
                explanation = stringResource(R.string.shadow_comparison_info),
                description = stringResource(
                    when (shadowLine(engineShowing(quickPicksSource), learnFromListening)) {
                        ShadowLine.UNUSED -> R.string.shadow_comparison_unused
                        ShadowLine.CHECKED -> R.string.shadow_comparison_description
                        ShadowLine.NOT_CHECKED -> R.string.shadow_comparison_not_learning
                    }
                ),
                checked = shadowComparison,
                onCheckedChange = onShadowComparisonChange,
            )
            if (numbers) {
                val rowNames = mapOf(
                    HeldRow.ENGINE to stringResource(R.string.recommendations_team_engine),
                    HeldRow.ENGINE_BEFORE to stringResource(R.string.recommendations_row_engine_before),
                    HeldRow.ENGINE_ALONE_BEFORE to stringResource(R.string.recommendations_row_engine_alone_before),
                    HeldRow.LIBRARY to stringResource(R.string.recommendations_team_library),
                    HeldRow.YOUTUBE to stringResource(R.string.recommendations_team_youtube),
                    HeldRow.UNSEEN to stringResource(R.string.recommendations_row_shadow),
                    HeldRow.TRY_BOTH to stringResource(R.string.recommendations_row_try_both),
                    HeldRow.TRY_BOTH_BEFORE to stringResource(R.string.recommendations_row_try_both_before),
                    HeldRow.DISCOVER to stringResource(R.string.discover_something_new),
                )
                StatEntry(
                    title = stringResource(R.string.recommendations_held),
                    explanation = stringResource(R.string.recommendations_held_info),
                    // A build is each time the row on screen changed, or the unseen one was built
                    // again: a refresh, which a reader can picture where "26 rows" was a puzzle.
                    // Each line says the songs it counts are those after its own refreshes, so two
                    // lines with different totals do not read as two counts of the same songs, and
                    // that a card you tapped is one of them. With none it says why there is nothing
                    // to compare.
                    numbers = held.map { l ->
                        val name = rowNames.getValue(l.row)
                        if (l.nothingToCompare) {
                            val after = if (oneInWords(l.refreshes)) stringResource(R.string.recommendations_after_refresh)
                                else pluralStringResource(R.plurals.recommendations_after_any_refresh, l.refreshes, l.refreshes)
                            stringResource(R.string.recommendations_held_nothing, name, after)
                        } else {
                            val after = if (oneInWords(l.refreshes)) stringResource(R.string.recommendations_after_refresh)
                                else pluralStringResource(R.plurals.recommendations_after_refreshes, l.refreshes, l.refreshes)
                            pluralStringResource(R.plurals.recommendations_held_songs, l.chosen, name, l.held, l.chosen, after)
                        }
                    }.joinToString("\n").ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                    meaning = stringResource(R.string.recommendations_held_meaning),
                )

                // The Brier score is the honest measure but means nothing to most people, so the
                // row compares what it expected with what happened, and the score waits at the foot
                // of the "i" as a detail for whoever wants one number. It says how many cards it
                // covers: only those from a scored row carry a guess, which can be fewer than the
                // summary counts. Chances are "in 100" all the way down, cards plain counts. The
                // footer says first whether its guesses beat the same chance for every card, named
                // as the share played, then the score beside that bar, since a small number alone
                // reads as good whatever it measures. With none played there is no score to give,
                // and the footer says only that.
                val brier = Calibration.brier(pairs)
                val prediction = predictionOf(pairs)
                val (expected, played) = per100Texts(prediction.expectedPer100, prediction.playedPer100, locale)
                StatEntry(
                    title = stringResource(R.string.recommendations_brier),
                    explanation = stringResource(R.string.recommendations_brier_info),
                    footer = brierFooter(pairs)?.let { f ->
                        when (f.verdict) {
                            BrierVerdict.NONE_PLAYED -> stringResource(R.string.recommendations_brier_none_played)
                            BrierVerdict.BETTER -> stringResource(R.string.recommendations_brier_better, played)
                            BrierVerdict.SAME -> stringResource(R.string.recommendations_brier_same, played)
                            BrierVerdict.WORSE -> stringResource(R.string.recommendations_brier_worse, played)
                        } + (if (f.score != null && f.reference != null) {
                            " " + pluralStringResource(R.plurals.recommendations_brier_description, pairs.size, f.score, pairs.size, f.reference)
                        } else "")
                    },
                    numbers = if (brier.isNaN()) stringResource(R.string.recommendations_nothing_yet)
                        else (listOf(
                            when (predictionLine(prediction)) {
                                PredictionLine.ONE_PLAYED -> stringResource(R.string.recommendations_predicted_of_single_played, expected)
                                PredictionLine.ONE_NOT_PLAYED -> stringResource(R.string.recommendations_predicted_of_single, expected)
                                PredictionLine.MANY -> pluralStringResource(R.plurals.recommendations_predicted_of, prediction.cards, prediction.cards, expected, played)
                            },
                        ) +
                            // map, not the joinToString below it directly: map is inline and can
                            // call a composable function, joinToString's own lambda cannot.
                            predictionBands(pairs).map { b ->
                                val playedText = if (b.played == 0) stringResource(R.string.recommendations_band_none_played)
                                    else pluralStringResource(R.plurals.recommendations_band_played, b.played, b.played)
                                if (b.fromPer100 == 0) stringResource(R.string.recommendations_band_under, b.toPer100, b.cards, playedText)
                                else stringResource(R.string.recommendations_band, b.fromPer100, b.toPer100, b.cards, playedText)
                            }).joinToString("\n"),
                    // As under Cards you saw: "Nothing yet" needs no line on what it means.
                    meaning = if (brier.isNaN()) null else stringResource(R.string.recommendations_predicted_meaning),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_learned_title),
            explanation = stringResource(R.string.recommendations_learned_title_info),
        )
        // Counts, unlike the figures above, so the one thing a reader needs before them is that
        // they are not scores.
        Text(
            text = stringResource(R.string.recommendations_learned_meaning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ExplainedPreference(
                title = pluralStringResource(R.plurals.recommendations_listens, listens, listens, counted),
                explanation = stringResource(R.string.recommendations_listens_info),
                description = stringResource(R.string.recommendations_listens_description),
            )
            ExplainedPreference(
                title = pluralStringResource(R.plurals.recommendations_sessions, sessions, sessions),
                explanation = stringResource(R.string.recommendations_sessions_info),
                description = stringResource(R.string.recommendations_sessions_description),
            )
            StatEntry(
                title = stringResource(R.string.recommendations_how_they_ended),
                explanation = stringResource(R.string.recommendations_how_they_ended_info),
                // Split the way Recent listens names them, so "ended early" there is a count here.
                numbers = endCounts(byEnd).joinToString(", ") { (label, n) -> "${endLabels[label]} $n" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                meaning = stringResource(R.string.recommendations_how_they_ended_meaning),
            )
            // Starts, not songs: counted by song, one tapped card that started a radio gave every
            // song after it, and "quick picks 4" stood above "2 tapped" under Cards you saw.
            StatEntry(
                title = stringResource(R.string.recommendations_where_from),
                explanation = stringResource(R.string.recommendations_where_from_info),
                numbers = originCounts(startsByOrigin).joinToString(", ") { (origin, n) -> "${originLabel(origin)} $n" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                meaning = stringResource(R.string.recommendations_where_from_meaning),
            )
            // By source, with the judged count first: the same number the summary and Cards played
            // give, and what is not judged yet or left out named beside it rather than added in.
            // It used to be every row in the table, Discover's, the unjudged and the pool picks
            // never on screen among them, "across" every build, and read as a contradiction of
            // the count at the top. Taps go on each source's line: "You tapped 2 of them." under
            // three lines left "them" to guess.
            val seen = cardsSeen(cardsSeenRows)
            StatEntry(
                title = stringResource(R.string.recommendations_cards_seen),
                explanation = stringResource(R.string.recommendations_impressions_info),
                numbers = seen.map { r ->
                    val name = teamNames[r.team] ?: r.team.toString()
                    (if (onlyWaiting(r)) {
                        pluralStringResource(R.plurals.recommendations_cards_seen_waiting, r.waiting, name, r.waiting)
                    } else {
                        stringResource(R.string.recommendations_cards_seen_line, name, r.judged) +
                            (if (r.waiting > 0) pluralStringResource(R.plurals.recommendations_cards_waiting, r.waiting, r.waiting) else "")
                    }) + (if (r.leftOut > 0) pluralStringResource(R.plurals.recommendations_cards_left_out, r.leftOut, r.leftOut) else "") +
                        (if (r.tapped > 0) pluralStringResource(R.plurals.recommendations_cards_tapped, r.tapped, r.tapped) else "")
                }.joinToString("\n").ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                meaning = if (seen.isEmpty()) null else stringResource(R.string.recommendations_impressions_description),
            )
            ExplainedPreference(
                title = pluralStringResource(R.plurals.recommendations_signals, signals, signals),
                explanation = stringResource(R.string.recommendations_signals_info),
                description = stringResource(R.string.recommendations_signals_description),
            )
        }
        Spacer(Modifier.height(16.dp))

        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_recent_title),
            explanation = stringResource(R.string.recommendations_recent_title_info),
        )
        Text(
            text = stringResource(R.string.recommendations_recent_meaning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        // The day goes beside the time when it was not today, so the list reads newest first.
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val dayFormat = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            if (recent.isEmpty()) {
                Text(
                    text = stringResource(R.string.recommendations_nothing_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            recent.forEach { row ->
                // How much was heard, not where it stopped: a song can reach the end after a jump,
                // or a stream give out, with only a little of it heard, and then it "ended early".
                // Unknown parts are left out. A minute or more reads as a clock does, 4:04.
                val end = endLabels[endLabel(row.endReason, row.ratio)] ?: notRecorded
                val length = clockLength(row.playedMs) ?: stringResource(R.string.recommendations_seconds, (row.playedMs / 1000).toInt())
                val heard = if (row.ratio >= 0f) {
                    stringResource(R.string.recommendations_recent_line, end, percentText((row.ratio * 100).toInt(), locale), length)
                } else {
                    stringResource(R.string.recommendations_recent_line_no_share, end, length)
                }
                val from = PlayOrigin.fromCode(row.origin)
                val origin = if (from == PlayOrigin.UNKNOWN) "" else stringResource(
                    R.string.recommendations_recent_from,
                    originLabel(from) + (if (row.originSlot >= 0) " #${row.originSlot + 1}" else ""),
                )
                val afterPick = if (row.autoplayDepth > 0) {
                    pluralStringResource(R.plurals.recommendations_recent_after_pick, row.autoplayDepth, row.autoplayDepth)
                } else ""
                val time = timeFormat.format(Date(row.endedAt))
                val ended = when (listenDay(row.endedAt, now, zone)) {
                    ListenDay.TODAY -> time
                    ListenDay.YESTERDAY -> stringResource(R.string.recommendations_recent_yesterday, time)
                    ListenDay.EARLIER -> stringResource(R.string.recommendations_recent_day_time, dayFormat.format(Date(row.endedAt)), time)
                }
                PreferenceEntry(
                    title = { Text(row.title) },
                    // Not learned from: after Forget, a listen still in this list and unmarked
                    // read as if the forget had not worked.
                    description = heard + origin + afterPick +
                            (if (row.counted) "" else stringResource(R.string.recommendations_recent_not_counted)) +
                            (if (row.learn) "" else stringResource(R.string.recommendations_recent_not_learned)) +
                            " · " + ended,
                    onClick = null,
                )
            }
        }

        // Last and under its own heading: the weights and the tuning are for whoever works on the
        // engine, and nobody else should take them for something to read or set.
        if (Unreleased.ENGINE) {
            Spacer(Modifier.height(24.dp))
            PreferenceGroupTitle(title = stringResource(R.string.recommendations_developers_title))
            if (showWeights) {
                WeightsEntry(weights, updates)
            }
            ExplainedPreference(
                title = stringResource(R.string.engine_developer),
                explanation = stringResource(R.string.engine_developer_info),
                description = stringResource(R.string.engine_developer_description),
                onClick = { navController.navigate("settings/recommendations/developer") },
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    FloatingTopBar(
        title = stringResource(R.string.recommendations_doing_title),
        navController = navController,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

/**
 * The plain answer at the top: what share of the engine's cards were played, and which way that
 * is going. When there is nothing to count it says why in one line instead.
 */
@Composable
private fun SummaryCard(summary: DoingSummary, locale: Locale) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 4.dp, bottom = 20.dp)) {
            Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                when (summary) {
                    // With its cards from before still waiting, it says they will count here,
                    // since Cards you saw below lists them.
                    // With Learn from listening off they wait until it is back on. Rows that held
                    // can still show its rows below, which the summary then says.
                    is DoingSummary.NotSource -> {
                        Text(
                            text = when (notSourceText(summary)) {
                                NotSourceText.NOTHING -> stringResource(R.string.recommendations_summary_not_source)
                                NotSourceText.WAITING_ONE -> stringResource(R.string.recommendations_summary_not_source_waiting_single)
                                NotSourceText.WAITING -> pluralStringResource(R.plurals.recommendations_summary_not_source_waiting, summary.waiting, summary.waiting)
                                NotSourceText.PAUSED_ONE -> stringResource(R.string.recommendations_summary_not_source_paused_single)
                                NotSourceText.PAUSED -> pluralStringResource(R.plurals.recommendations_summary_not_source_paused, summary.waiting, summary.waiting)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (summary.rowsBelow) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.recommendations_summary_rows_below),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!summary.learning) LearningOffLine()
                    }
                    DoingSummary.Waiting -> Text(
                        text = stringResource(R.string.recommendations_summary_waiting),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DoingSummary.NotLearning -> Text(
                        text = stringResource(R.string.recommendations_summary_not_learning),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    is DoingSummary.Numbers -> {
                        // One card reads "You played the one card", not "0 of the 1 card, about 0 in 100".
                        Text(
                            text = when {
                                !oneInWords(summary.cards.seen) -> pluralStringResource(
                                    R.plurals.recommendations_summary_played, summary.cards.seen,
                                    summary.cards.played, summary.cards.seen, per100Text(summary.cards.per100, locale),
                                )
                                summary.cards.played > 0 -> stringResource(R.string.recommendations_summary_played_single)
                                else -> stringResource(R.string.recommendations_summary_not_played_single)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        // The cards it showed that are not judged yet, so the count above and
                        // Cards you saw further down are plainly the same cards, with the one
                        // line on the page that says when a card is judged.
                        if (summary.waiting > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (oneInWords(summary.waiting)) stringResource(R.string.recommendations_summary_waiting_more_single)
                                    else pluralStringResource(R.plurals.recommendations_summary_waiting_more, summary.waiting, summary.waiting),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // In place of the line on cards waiting, which would promise a judging
                        // that does not come while it is off.
                        if (!summary.learning) LearningOffLine()
                        Spacer(Modifier.height(12.dp))
                        summary.trend?.let {
                            TrendLine(it, locale)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            text = stringResource(R.string.recommendations_summary_scale),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (summary.fromBefore) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.recommendations_summary_from_before),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            ExplainButton(
                title = stringResource(R.string.recommendations_doing_title),
                body = stringResource(R.string.recommendations_summary_info),
            )
        }
    }
}

/** With Learn from listening off: it is not learning now, so nothing new is judged, and where to turn it on. */
@Composable
private fun LearningOffLine() {
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.recommendations_summary_learning_off),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun TrendLine(trend: Trend, locale: Locale) {
    val (icon, text) = when (trend) {
        Trend.TooEarly -> null to stringResource(R.string.recommendations_summary_too_early)
        Trend.TooEarlySinceChoice -> null to stringResource(R.string.recommendations_summary_too_early_choice)
        is Trend.Up -> per100Texts(trend.recent, trend.earlier, locale).let { (a, b) ->
            Icons.AutoMirrored.Rounded.TrendingUp to stringResource(R.string.recommendations_summary_up, a, b)
        }
        is Trend.Down -> per100Texts(trend.recent, trend.earlier, locale).let { (a, b) ->
            Icons.AutoMirrored.Rounded.TrendingDown to stringResource(R.string.recommendations_summary_down, a, b)
        }
        is Trend.NoClearChange -> per100Texts(trend.recent, trend.earlier, locale).let { (a, b) ->
            Icons.AutoMirrored.Rounded.TrendingFlat to stringResource(R.string.recommendations_summary_no_change, a, b)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * A figure with its explanation: the numbers where a setting's description would be, then one
 * plain line on what they mean and whether higher is better, and the full working behind the "i",
 * with [footer] under it for the precise figure a plain line leaves out. No [meaning] when the
 * numbers already say there is nothing to read.
 */
@Composable
internal fun StatEntry(
    title: String,
    explanation: String,
    numbers: String,
    meaning: String?,
    footer: String? = null,
) = PreferenceEntry(
    title = { Text(title) },
    description = numbers,
    content = meaning?.let { m -> @Composable { MeaningLine(m) } },
    trailingContent = { ExplainButton(title = title, body = explanation, footer = footer) },
    onClick = null,
)

/** The one plain line under a figure, or under a choice: what it means as things stand. */
@Composable
internal fun MeaningLine(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
)

/** What each thing counts for in a card's score now, beside where it started. */
@Composable
private fun WeightsEntry(weights: List<EngineWeight>, updates: Int) {
    val weightNames = mapOf(
        "x_act" to stringResource(R.string.weight_act), "x_sat" to stringResource(R.string.weight_sat), "x_gap" to stringResource(R.string.weight_gap),
        "x_dorm" to stringResource(R.string.weight_dorm), "x_like" to stringResource(R.string.weight_like), "x_seed" to stringResource(R.string.weight_seed),
        "x_art" to stringResource(R.string.weight_art), "x_novel" to stringResource(R.string.weight_novel), "x_imp" to stringResource(R.string.weight_imp),
        "x_co" to stringResource(R.string.weight_co), "x_ctx" to stringResource(R.string.weight_ctx), "x_over" to stringResource(R.string.weight_over),
        "w_pos" to stringResource(R.string.weight_pos), "b" to stringResource(R.string.weight_bias),
    )
    val started = stringResource(R.string.recommendations_weight_started)
    StatEntry(
        // Learning steps, not cards: a pool pick, a song from the spare pool behind the row that
        // you played, is one too, and it was never on screen. Cards would have counted more than
        // the page says it showed.
        title = pluralStringResource(R.plurals.recommendations_weights, updates, updates),
        explanation = stringResource(R.string.recommendations_weights_info),
        numbers = Features.priors.keys.filter { it in weightNames }.joinToString("\n") { name ->
            val row = weights.firstOrNull { it.name == name }
            val prior = Features.priors[name]!!.value
            "%s: %.2f (%s %.2f)".format(weightNames[name], row?.value ?: prior, started, prior)
        },
        meaning = stringResource(R.string.recommendations_weights_meaning),
    )
}
