/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.ui.component.SwitchPreference
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
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
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
        PreferenceGroupTitle(title = stringResource(R.string.recommendations_home_title))
        SwitchPreference(
            title = { Text(stringResource(R.string.tidy_home_rows)) },
            description = stringResource(R.string.tidy_home_rows_description),
            checked = tidyHomeRows,
            onCheckedChange = onTidyHomeRowsChange,
        )
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_learned_title))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_listens, listens, counted)) },
                description = stringResource(R.string.recommendations_listens_description),
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_sessions, sessions)) },
                description = stringResource(R.string.recommendations_sessions_description),
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_how_they_ended)) },
                description = byEndReason.joinToString(", ") { "${endReasonLabel(it.code)} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_where_from)) },
                description = byOrigin.joinToString(", ") { "${PlayOrigin.fromCode(it.code).name.lowercase().replace('_', ' ')} ${it.n}" }
                    .ifBlank { stringResource(R.string.recommendations_nothing_yet) },
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_impressions, impressions, rowBuilds)) },
                description = stringResource(R.string.recommendations_impressions_description),
                onClick = null,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.recommendations_signals, signals, taps)) },
                description = stringResource(R.string.recommendations_signals_description),
                onClick = null,
            )
        }
        Spacer(Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.recommendations_recent_title))
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

