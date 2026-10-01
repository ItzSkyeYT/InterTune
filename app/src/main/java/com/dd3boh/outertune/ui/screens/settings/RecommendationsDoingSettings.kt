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
import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.QuickPicksSourceKey
import com.dd3boh.outertune.constants.ShadowComparisonKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.EngineWeight
import com.dd3boh.outertune.engine.Calibration
import com.dd3boh.outertune.engine.DoingSummary
import com.dd3boh.outertune.engine.Features
import com.dd3boh.outertune.engine.Trend
import com.dd3boh.outertune.engine.cardsByTeam
import com.dd3boh.outertune.engine.doingSummary
import com.dd3boh.outertune.engine.engineShowing
import com.dd3boh.outertune.engine.hasNumbers
import com.dd3boh.outertune.engine.per100Text
import com.dd3boh.outertune.engine.per100Texts
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
import com.dd3boh.outertune.viewmodels.DISCOVER_ROW_KEY
import com.dd3boh.outertune.viewmodels.DISCOVER_TEAM
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel
import java.text.DateFormat
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
    val byEndReason by viewModel.byEndReason.collectAsState(initial = emptyList())
    val byOrigin by viewModel.byOrigin.collectAsState(initial = emptyList())
    val impressions by viewModel.impressions.collectAsState(initial = 0)
    val rowBuilds by viewModel.rowBuilds.collectAsState(initial = 0)
    val signals by viewModel.signals.collectAsState(initial = 0)
    val taps by viewModel.taps.collectAsState(initial = 0)
    val recent by viewModel.recent.collectAsState(initial = emptyList())
    // Null until read, so the summary does not say "nothing yet" for the moment before it knows.
    val gradedByTeam by viewModel.gradedByTeam.collectAsState(initial = null)
    val cardTrend by viewModel.cardTrend.collectAsState(initial = null)
    val calibration by viewModel.calibration.collectAsState(initial = emptyList())
    val weights by viewModel.weights.collectAsState(initial = emptyList())
    val buildScores by viewModel.buildScores.collectAsState(initial = emptyList())
    val (shadowComparison, onShadowComparisonChange) = rememberPreference(ShadowComparisonKey, defaultValue = true)
    val (quickPicksSource, _) = rememberEnumPreference(QuickPicksSourceKey, defaultValue = QuickPicksSource.YOUTUBE)
    val locale = Locale.getDefault()
    val updates = weights.maxOfOrNull { it.updates } ?: 0

    val endReasonLabels = mapOf(
        EndReason.ENDED to stringResource(R.string.recommendations_ended),
        EndReason.SKIPPED to stringResource(R.string.recommendations_skipped),
        EndReason.REPLACED to stringResource(R.string.recommendations_replaced),
        EndReason.STOPPED to stringResource(R.string.recommendations_stopped),
    )
    // Lower case, like the labels it stands among.
    val unknown = stringResource(R.string.recommendations_origin_unknown)
    fun endReasonLabel(code: Int) = endReasonLabels[code] ?: unknown
    val originLabels = mapOf(
        PlayOrigin.UNKNOWN to stringResource(R.string.recommendations_origin_unknown),
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
    fun originLabel(code: Int) = originLabels[PlayOrigin.fromCode(code)] ?: unknown

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

            SummaryCard(doingSummary(engineShowing(quickPicksSource), teams, cardTrend), locale)
            Spacer(Modifier.height(16.dp))

            ExplainedGroupTitle(
                title = stringResource(R.string.recommendations_engine_title),
                explanation = stringResource(R.string.recommendations_doing_title_info),
            )
            if (numbers) {
                val teamNames = mapOf(1 to stringResource(R.string.recommendations_team_engine), 2 to stringResource(R.string.recommendations_team_library), 3 to stringResource(R.string.recommendations_team_youtube), DISCOVER_TEAM to stringResource(R.string.discover_something_new))
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
            // recommendations already in the row it builds nothing, and its line says so.
            ExplainedSwitchPreference(
                title = stringResource(R.string.shadow_comparison),
                explanation = stringResource(R.string.shadow_comparison_info),
                description = stringResource(
                    if (engineShowing(quickPicksSource)) R.string.shadow_comparison_unused else R.string.shadow_comparison_description
                ),
                checked = shadowComparison,
                onCheckedChange = onShadowComparisonChange,
            )
            if (numbers) {
                val rowNames = mapOf(1 to stringResource(R.string.recommendations_team_engine), 2 to stringResource(R.string.recommendations_team_library), 3 to stringResource(R.string.recommendations_team_youtube), 4 to stringResource(R.string.recommendations_row_shadow), DISCOVER_ROW_KEY to stringResource(R.string.discover_something_new))
                StatEntry(
                    title = stringResource(R.string.recommendations_held),
                    explanation = stringResource(R.string.recommendations_held_info),
                    numbers = buildScores.sortedBy { it.rowKey }.map { b ->
                        pluralStringResource(
                            R.plurals.recommendations_held_songs, b.plays,
                            rowNames[b.rowKey] ?: b.rowKey.toString(), b.hits, b.plays,
                            pluralStringResource(R.plurals.recommendations_rows, b.builds, b.builds),
                        )
                    }.joinToString("\n").ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                    meaning = stringResource(R.string.recommendations_held_meaning),
                )

                // The Brier score is the honest measure but means nothing to most people, so the
                // row compares what it expected with what happened, and the score itself waits
                // behind the "i" for whoever wants it. It says how many cards it covers: only those
                // from a scored row carry a guess, which can be fewer than the summary counts.
                val brier = Calibration.brier(pairs)
                val prediction = predictionOf(pairs)
                val (expected, played) = per100Texts(prediction.expectedPer100, prediction.playedPer100, locale)
                StatEntry(
                    title = stringResource(R.string.recommendations_brier),
                    explanation = stringResource(R.string.recommendations_brier_info),
                    footer = if (brier.isNaN()) null
                        else pluralStringResource(R.plurals.recommendations_brier_description, pairs.size, brier, pairs.size),
                    numbers = if (brier.isNaN()) stringResource(R.string.recommendations_nothing_yet)
                        else (listOf(
                            pluralStringResource(R.plurals.recommendations_predicted_of, prediction.cards, prediction.cards, expected, played),
                            stringResource(R.string.recommendations_by_chance),
                        ) +
                            // map, not the joinToString below it directly: map is inline and can
                            // call a composable function, joinToString's own lambda cannot.
                            Calibration.reliability(pairs).filter { it.count > 0 }.map { b ->
                                pluralStringResource(
                                    R.plurals.recommendations_calibration_bucket, b.count,
                                    b.lo * 100, b.hi * 100, b.count, b.playRate * 100
                                )
                            }).joinToString("\n"),
                    meaning = stringResource(R.string.recommendations_predicted_meaning),
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
                title = stringResource(R.string.recommendations_listens, listens, counted),
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
                numbers = byEndReason.joinToString(", ") { "${endReasonLabel(it.code)} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                meaning = stringResource(R.string.recommendations_how_they_ended_meaning),
            )
            StatEntry(
                title = stringResource(R.string.recommendations_where_from),
                explanation = stringResource(R.string.recommendations_where_from_info),
                numbers = byOrigin.joinToString(", ") { "${originLabel(it.code)} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                meaning = stringResource(R.string.recommendations_where_from_meaning),
            )
            ExplainedPreference(
                title = pluralStringResource(
                    R.plurals.recommendations_impressions_shown, impressions,
                    impressions, pluralStringResource(R.plurals.recommendations_rows, rowBuilds, rowBuilds),
                ),
                explanation = stringResource(R.string.recommendations_impressions_info),
                description = stringResource(R.string.recommendations_impressions_description),
            )
            ExplainedPreference(
                title = stringResource(R.string.recommendations_signals, signals, taps),
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
                // or a stream give out, with only a little of it heard. Unknown parts are left out.
                val heard = if (row.ratio >= 0f) {
                    stringResource(R.string.recommendations_recent_line, endReasonLabel(row.endReason), "${(row.ratio * 100).toInt()}%", row.playedMs / 1000)
                } else {
                    stringResource(R.string.recommendations_recent_line_no_share, endReasonLabel(row.endReason), row.playedMs / 1000)
                }
                val origin = if (PlayOrigin.fromCode(row.origin) == PlayOrigin.UNKNOWN) "" else stringResource(
                    R.string.recommendations_recent_from,
                    originLabel(row.origin) + (if (row.originSlot >= 0) " #${row.originSlot + 1}" else ""),
                )
                val afterPick = if (row.autoplayDepth > 0) {
                    pluralStringResource(R.plurals.recommendations_recent_after_pick, row.autoplayDepth, row.autoplayDepth)
                } else ""
                PreferenceEntry(
                    title = { Text(row.title) },
                    description = heard + origin + afterPick +
                            (if (row.counted) "" else stringResource(R.string.recommendations_recent_not_counted)) +
                            " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.endedAt)),
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
                    DoingSummary.NotSource -> Text(
                        text = stringResource(R.string.recommendations_summary_not_source),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DoingSummary.Waiting -> Text(
                        text = stringResource(R.string.recommendations_summary_waiting),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    is DoingSummary.Numbers -> {
                        Text(
                            text = pluralStringResource(
                                R.plurals.recommendations_summary_played, summary.cards.seen,
                                summary.cards.played, summary.cards.seen, per100Text(summary.cards.per100, locale),
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
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

@Composable
private fun TrendLine(trend: Trend, locale: Locale) {
    val (icon, text) = when (trend) {
        Trend.TooEarly -> null to stringResource(R.string.recommendations_summary_too_early)
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
    content = meaning?.let { m ->
        @Composable {
            Text(
                text = m,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    },
    trailingContent = { ExplainButton(title = title, body = explanation, footer = footer) },
    onClick = null,
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
