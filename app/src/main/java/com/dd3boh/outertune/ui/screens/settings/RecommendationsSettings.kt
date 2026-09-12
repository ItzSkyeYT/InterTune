/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.constants.RestsEverywhereKey
import com.dd3boh.outertune.constants.RestSongsISkipKey
import com.dd3boh.outertune.constants.ShadowComparisonKey
import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.constants.FamiliarityKey
import com.dd3boh.outertune.engine.Features
import com.dd3boh.outertune.engine.Calibration
import com.dd3boh.outertune.constants.LearnFromListeningKey
import com.dd3boh.outertune.constants.NewSongsOnlyKey
import androidx.compose.material3.Slider
import com.dd3boh.outertune.engine.quotas
import com.dd3boh.outertune.engine.Lane
import com.dd3boh.outertune.constants.AdventurousnessKey
import com.dd3boh.outertune.constants.ShowReasonsKey
import com.dd3boh.outertune.constants.RankWithListeningKey
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.ui.component.ExplainedGroupTitle
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.constants.TidyHomeRowsKey
import androidx.compose.foundation.layout.Column
import com.dd3boh.outertune.ui.component.button.IconButton
import androidx.compose.foundation.layout.fillMaxHeight
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import androidx.compose.material3.TopAppBar
import com.dd3boh.outertune.constants.TopBarInsets
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel
import java.text.DateFormat
import java.util.Date

/**
 * What the app has learned about how you listen, and, in time, how Quick picks uses it.
 *
 * Shown in the open rather than hidden behind a developer flag, because a recommendation that
 * cannot explain itself is not one anybody should be asked to trust. For now this is the ledger:
 * every stop the engine will learn from, with the two facts about it that matter most.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsSettings(
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
    val (tidyHomeRows, onTidyHomeRowsChange) = rememberPreference(TidyHomeRowsKey, defaultValue = true)
    val (rankWithListening, onRankWithListeningChange) = rememberPreference(RankWithListeningKey, defaultValue = true)
    val (showReasons, onShowReasonsChange) = rememberPreference(ShowReasonsKey, defaultValue = true)
    val (adventurousness, onAdventurousnessChange) = rememberPreference(AdventurousnessKey, defaultValue = 15)
    val (newSongsOnly, onNewSongsOnlyChange) = rememberPreference(NewSongsOnlyKey, defaultValue = false)
    val (familiarity, onFamiliarityChange) = rememberPreference(FamiliarityKey, defaultValue = 25)
    val activeExclusions by viewModel.activeExclusions.collectAsState(initial = 0)
    val gradedByTeam by viewModel.gradedByTeam.collectAsState(initial = emptyList())
    val calibration by viewModel.calibration.collectAsState(initial = emptyList())
    val weights by viewModel.weights.collectAsState(initial = emptyList())
    val (learnFromListening, onLearnFromListeningChange) = rememberPreference(LearnFromListeningKey, defaultValue = true)
    val (shadowComparison, onShadowComparisonChange) = rememberPreference(ShadowComparisonKey, defaultValue = true)
    val (restSongsISkip, onRestSongsISkipChange) = rememberPreference(RestSongsISkipKey, defaultValue = false)
    val (restsEverywhere, onRestsEverywhereChange) = rememberPreference(RestsEverywhereKey, defaultValue = false)
    val context = LocalContext.current
    val buildScores by viewModel.buildScores.collectAsState(initial = emptyList())
    val endReasonLabels = mapOf(
        EndReason.ENDED to stringResource(R.string.recommendations_ended),
        EndReason.SKIPPED to stringResource(R.string.recommendations_skipped),
        EndReason.REPLACED to stringResource(R.string.recommendations_replaced),
        EndReason.STOPPED to stringResource(R.string.recommendations_stopped),
    )
    val unknown = stringResource(R.string.unknown)
    fun endReasonLabel(code: Int) = endReasonLabels[code] ?: unknown

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_home_title),
            explanation = stringResource(R.string.recommendations_home_title_info),
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.tidy_home_rows),
            explanation = stringResource(R.string.tidy_home_rows_info),
            description = stringResource(R.string.tidy_home_rows_description),
            checked = tidyHomeRows,
            onCheckedChange = onTidyHomeRowsChange,
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.rank_with_listening),
            explanation = stringResource(R.string.rank_with_listening_info),
            description = stringResource(R.string.rank_with_listening_description),
            checked = rankWithListening,
            onCheckedChange = onRankWithListeningChange,
        )
        Spacer(Modifier.height(16.dp))

        // The engine's own controls. Choosing it is done where the source is chosen, under Content.
        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_engine_title),
            explanation = stringResource(R.string.recommendations_engine_title_info),
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.show_reasons),
            explanation = stringResource(R.string.show_reasons_info),
            description = stringResource(R.string.show_reasons_description),
            checked = showReasons,
            onCheckedChange = onShowReasonsChange,
        )
        ExplainedPreference(
            title = stringResource(R.string.adventurousness),
            explanation = stringResource(R.string.adventurousness_info),
            description = stringResource(R.string.adventurousness_description, quotas(20, adventurousness / 100.0, false)[Lane.EXPLORE] ?: 0),
        )
        Slider(
            value = adventurousness.toFloat(),
            onValueChange = { onAdventurousnessChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ExplainedPreference(
            title = stringResource(R.string.familiarity),
            explanation = stringResource(R.string.familiarity_info),
            description = stringResource(R.string.familiarity_description, quotas(20, adventurousness / 100.0, false, EngineParams.DEFAULT.withFamiliarity(familiarity / 100.0))[Lane.AGAIN] ?: 0),
        )
        Slider(
            value = familiarity.toFloat(),
            onValueChange = { onFamiliarityChange(it.toInt()) },
            valueRange = 0f..60f,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.new_songs_only),
            explanation = stringResource(R.string.new_songs_only_info),
            description = stringResource(R.string.new_songs_only_description),
            checked = newSongsOnly,
            onCheckedChange = onNewSongsOnlyChange,
        )
        ExplainedPreference(
            title = stringResource(R.string.exclusions),
            explanation = stringResource(R.string.exclusions_info),
            description = stringResource(R.string.exclusions_count, activeExclusions),
            onClick = { navController.navigate("settings/recommendations/exclusions") },
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.rest_songs_i_skip),
            explanation = stringResource(R.string.rest_songs_i_skip_info),
            description = stringResource(R.string.rest_songs_i_skip_description),
            checked = restSongsISkip,
            onCheckedChange = onRestSongsISkipChange,
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.rests_everywhere),
            explanation = stringResource(R.string.rests_everywhere_info),
            description = stringResource(R.string.rests_everywhere_description),
            checked = restsEverywhere,
            onCheckedChange = onRestsEverywhereChange,
            isEnabled = restSongsISkip,
        )
        Spacer(Modifier.height(16.dp))

        // How it's doing: what was shown, what was played, how well the predictions matched, and
        // each weight beside where it started.
        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_doing_title),
            explanation = stringResource(R.string.recommendations_doing_title_info),
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.learn_from_listening),
            explanation = stringResource(R.string.learn_from_listening_info),
            description = stringResource(R.string.learn_from_listening_description),
            checked = learnFromListening,
            onCheckedChange = onLearnFromListeningChange,
        )
        val teamNames = mapOf(1 to stringResource(R.string.recommendations_team_engine), 2 to stringResource(R.string.recommendations_team_library), 3 to stringResource(R.string.recommendations_team_youtube))
        val scored = gradedByTeam.filter { it.outcome in 1..3 }.groupBy { it.team }
        val winsLine = stringResource(R.string.recommendations_wins_line)
        ExplainedPreference(
            title = stringResource(R.string.recommendations_wins),
            explanation = stringResource(R.string.recommendations_wins_info),
            description = scored.entries.sortedBy { it.key }.joinToString("\n") { (team, rows) ->
                val seen = rows.sumOf { it.n }; val wins = rows.sumOf { it.wins }
                String.format(winsLine, teamNames[team] ?: team.toString(), wins, seen, if (seen > 0) 100.0 * wins / seen else 0.0)
            }.ifBlank { stringResource(R.string.recommendations_nothing_yet) },
        )
        ExplainedSwitchPreference(
            title = stringResource(R.string.shadow_comparison),
            explanation = stringResource(R.string.shadow_comparison_info),
            description = stringResource(R.string.shadow_comparison_description),
            checked = shadowComparison,
            onCheckedChange = onShadowComparisonChange,
        )
        val rowNames = mapOf(1 to stringResource(R.string.recommendations_team_engine), 2 to stringResource(R.string.recommendations_team_library), 3 to stringResource(R.string.recommendations_team_youtube), 4 to stringResource(R.string.recommendations_row_shadow))
        val heldLine = stringResource(R.string.recommendations_held_line)
        ExplainedPreference(
            title = stringResource(R.string.recommendations_held),
            explanation = stringResource(R.string.recommendations_held_info),
            description = buildScores.sortedBy { it.rowKey }.joinToString("\n") { b ->
                String.format(heldLine, rowNames[b.rowKey] ?: b.rowKey.toString(), b.hits, b.plays, if (b.plays > 0) 100.0 * b.hits / b.plays else 0.0, b.builds)
            }.ifBlank { stringResource(R.string.recommendations_nothing_yet) },
        )
        val pairs = calibration.map { it.p.toDouble() to it.y.toDouble() }
        val brier = Calibration.brier(pairs)
        ExplainedPreference(
            title = stringResource(R.string.recommendations_brier),
            explanation = stringResource(R.string.recommendations_brier_info),
            description = if (brier.isNaN()) stringResource(R.string.recommendations_nothing_yet)
                else stringResource(R.string.recommendations_brier_description, brier, pairs.size) + "\n" +
                    Calibration.reliability(pairs).filter { it.count > 0 }.joinToString("\n") { b -> "%.0f%% to %.0f%%: %d cards, %.0f%% played".format(b.lo * 100, b.hi * 100, b.count, b.playRate * 100) },
        )
        val weightNames = mapOf(
            "x_act" to stringResource(R.string.weight_act), "x_sat" to stringResource(R.string.weight_sat), "x_gap" to stringResource(R.string.weight_gap),
            "x_dorm" to stringResource(R.string.weight_dorm), "x_like" to stringResource(R.string.weight_like), "x_seed" to stringResource(R.string.weight_seed),
            "x_art" to stringResource(R.string.weight_art), "x_novel" to stringResource(R.string.weight_novel), "x_imp" to stringResource(R.string.weight_imp),
            "x_co" to stringResource(R.string.weight_co), "x_ctx" to stringResource(R.string.weight_ctx), "x_over" to stringResource(R.string.weight_over),
            "w_pos" to stringResource(R.string.weight_pos), "b" to stringResource(R.string.weight_bias),
        )
        val updates = weights.maxOfOrNull { it.updates } ?: 0
        val started = stringResource(R.string.recommendations_weight_started)
        ExplainedPreference(
            title = stringResource(R.string.recommendations_weights, updates),
            explanation = stringResource(R.string.recommendations_weights_info),
            description = Features.priors.keys.filter { it in weightNames }.joinToString("\n") { name ->
                val row = weights.firstOrNull { it.name == name }
                val prior = Features.priors[name]!!.value
                "%s: %.2f (%s %.2f)".format(weightNames[name], row?.value ?: prior, started, prior)
            },
        )
        ExplainedPreference(
            title = stringResource(R.string.recommendations_reset_weights),
            explanation = stringResource(R.string.recommendations_reset_weights_info),
            description = stringResource(R.string.recommendations_reset_weights_description),
            onClick = { viewModel.resetWeights() },
        )
        ExplainedPreference(
            title = stringResource(R.string.recommendations_rebuild_weights),
            explanation = stringResource(R.string.recommendations_rebuild_weights_info),
            description = stringResource(R.string.recommendations_rebuild_weights_description),
            onClick = { viewModel.rebuildWeights() },
        )
        ExplainedPreference(
            title = stringResource(R.string.forget_last_session),
            explanation = stringResource(R.string.forget_last_session_info),
            description = stringResource(R.string.forget_last_session_description),
            onClick = { viewModel.forgetLastSession() },
        )
        ExplainedPreference(
            title = stringResource(R.string.forget_today),
            explanation = stringResource(R.string.forget_today_info),
            description = stringResource(R.string.forget_today_description),
            onClick = { viewModel.forgetToday() },
        )
        ExplainedPreference(
            title = stringResource(R.string.engine_developer),
            explanation = stringResource(R.string.engine_developer_info),
            description = stringResource(R.string.engine_developer_description),
            onClick = { navController.navigate("settings/recommendations/developer") },
        )
        ExplainedPreference(
            title = stringResource(R.string.export_engine_data),
            explanation = stringResource(R.string.export_engine_data_info),
            description = stringResource(R.string.export_engine_data_description),
            onClick = {
                viewModel.export { json ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(android.content.Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, null))
                }
            },
        )
        Spacer(Modifier.height(16.dp))

        ExplainedGroupTitle(
            title = stringResource(R.string.recommendations_learned_title),
            explanation = stringResource(R.string.recommendations_learned_title_info),
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ExplainedPreference(
                title = stringResource(R.string.recommendations_listens, listens, counted),
                explanation = stringResource(R.string.recommendations_listens_info),
                description = stringResource(R.string.recommendations_listens_description),
            )
            ExplainedPreference(
                title = stringResource(R.string.recommendations_sessions, sessions),
                explanation = stringResource(R.string.recommendations_sessions_info),
                description = stringResource(R.string.recommendations_sessions_description),
            )
            ExplainedPreference(
                title = stringResource(R.string.recommendations_how_they_ended),
                explanation = stringResource(R.string.recommendations_how_they_ended_info),
                description = byEndReason.joinToString(", ") { "${endReasonLabel(it.code)} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
            )
            ExplainedPreference(
                title = stringResource(R.string.recommendations_where_from),
                explanation = stringResource(R.string.recommendations_where_from_info),
                description = byOrigin.joinToString(", ") { "${PlayOrigin.fromCode(it.code).name.lowercase().replace('_', ' ')} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
            )
            ExplainedPreference(
                title = stringResource(R.string.recommendations_impressions, impressions, rowBuilds),
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
                val pct = if (row.ratio >= 0f) "${(row.ratio * 100).toInt()}%" else "?"
                val origin = PlayOrigin.fromCode(row.origin).name.lowercase().replace('_', ' ') +
                        (if (row.originSlot >= 0) " #${row.originSlot + 1}" else "") +
                        (if (row.autoplayDepth > 0) ", autoplay ${row.autoplayDepth}" else "")
                PreferenceEntry(
                    title = { Text(row.title) },
                    description = "${endReasonLabel(row.endReason)} at $pct, ${row.playedMs / 1000}s, from $origin" +
                            (if (row.counted) "" else ", not counted") +
                            " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.endedAt)),
                    onClick = null,
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.recommendations)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

