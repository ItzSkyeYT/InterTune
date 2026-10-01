/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.ManageHistory
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AdventurousnessKey
import com.dd3boh.outertune.constants.DefaultAdventurousness
import com.dd3boh.outertune.constants.DiscoverRowKey
import com.dd3boh.outertune.constants.FamiliarityKey
import com.dd3boh.outertune.constants.LearnFromListeningKey
import com.dd3boh.outertune.constants.NewSongsOnlyKey
import com.dd3boh.outertune.constants.RankWithListeningKey
import com.dd3boh.outertune.constants.RestSongsISkipKey
import com.dd3boh.outertune.constants.RestsEverywhereKey
import com.dd3boh.outertune.constants.ShowReasonsKey
import com.dd3boh.outertune.constants.SimilarFromLastFmKey
import com.dd3boh.outertune.constants.SimilarSource
import com.dd3boh.outertune.constants.SimilarSourceKey
import com.dd3boh.outertune.constants.TidyHomeRowsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.engine.Lane
import com.dd3boh.outertune.engine.SimilarSources
import com.dd3boh.outertune.engine.quotas
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.ExplainButton
import com.dd3boh.outertune.ui.component.ExplainedGroupTitle
import com.dd3boh.outertune.ui.component.ExplainedPreference
import com.dd3boh.outertune.ui.component.ExplainedSwitchPreference
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.utils.BuiltInKeys
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.RecommendationsViewModel
import kotlin.math.roundToInt

/**
 * The recommendation settings people change: Home's rows, the engine's own controls, and what it
 * learns from. The numbers and the data buttons are one step further in, under How it's doing
 * and Your data, so the switches are not buried among statistics.
 *
 * Shown in the open rather than hidden behind a developer flag, because a recommendation that
 * cannot explain itself is not one anybody should be asked to trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RecommendationsViewModel = hiltViewModel(),
) {
    val (tidyHomeRows, onTidyHomeRowsChange) = rememberPreference(TidyHomeRowsKey, defaultValue = true)
    val (rankWithListening, onRankWithListeningChange) = rememberPreference(RankWithListeningKey, defaultValue = true)
    val (showReasons, onShowReasonsChange) = rememberPreference(ShowReasonsKey, defaultValue = true)
    val (adventurousness, onAdventurousnessChange) = rememberPreference(AdventurousnessKey, defaultValue = DefaultAdventurousness)
    val (newSongsOnly, onNewSongsOnlyChange) = rememberPreference(NewSongsOnlyKey, defaultValue = false)
    val (discoverRow, onDiscoverRowChange) = rememberPreference(DiscoverRowKey, defaultValue = true)
    val (familiarity, onFamiliarityChange) = rememberPreference(FamiliarityKey, defaultValue = 25)
    val activeExclusions by viewModel.activeExclusions.collectAsState(initial = 0)
    val (learnFromListening, onLearnFromListeningChange) = rememberPreference(LearnFromListeningKey, defaultValue = true)
    val (restSongsISkip, onRestSongsISkipChange) = rememberPreference(RestSongsISkipKey, defaultValue = false)
    val (restsEverywhere, onRestsEverywhereChange) = rememberPreference(RestsEverywhereKey, defaultValue = false)
    val context = LocalContext.current
    // Unset, the old Last.fm switch decides, as it does everywhere else; see SimilarSources.stored.
    val similarDefault = remember { SimilarSources.stored(null, context.dataStore[SimilarFromLastFmKey]) }
    val (similarSource, onSimilarSourceChange) = rememberEnumPreference(SimilarSourceKey, similarDefault)
    val sourceMix by viewModel.sourceMix.collectAsState(initial = null)

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
        // Built by the engine, so held back with it.
        if (Unreleased.ENGINE) {
            ExplainedSwitchPreference(
                title = stringResource(R.string.discover_something_new),
                explanation = stringResource(R.string.discover_row_info),
                description = stringResource(R.string.discover_row_description),
                checked = discoverRow,
                onCheckedChange = onDiscoverRowChange,
            )
        }
        Spacer(Modifier.height(16.dp))

        // Held back for 0.11 with the row they steer: see Unreleased. What stays visible without
        // them is the log and its ledger, under How it's doing, which is the part a listener is
        // owed either way.
        if (Unreleased.ENGINE) {
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
            // Needs the Last.fm key built into the app. A build without one, or a copy not signed with
            // the release key, has nothing to ask with, so the choice is not shown rather than shown
            // doing nothing, and the app acts as YouTube only.
            if (BuiltInKeys.lastFmApiKey.isNotEmpty()) {
                val similarTitle = stringResource(R.string.similar_source)
                val similarInfo = stringResource(R.string.similar_source_info)
                EnumListPreference(
                    title = { Text(similarTitle) },
                    icon = null,
                    trailingContent = { ExplainButton(similarTitle, similarInfo) },
                    selectedValue = similarSource,
                    valueText = {
                        when (it) {
                            SimilarSource.BOTH -> stringResource(R.string.similar_source_both)
                            SimilarSource.YOUTUBE -> stringResource(R.string.similar_source_youtube)
                            SimilarSource.LASTFM -> stringResource(R.string.similar_source_lastfm)
                        }
                    },
                    onValueSelected = onSimilarSourceChange,
                )
                // What Both has learned so far, beside the choice it follows from. Only with Both:
                // with one source nothing is shared, so there is no split to show.
                if (SimilarSources.showsSplit(similarSource, hasKey = true, engineOn = Unreleased.ENGINE)) {
                    val mix = sourceMix
                    StatEntry(
                        title = stringResource(R.string.similar_mix),
                        explanation = stringResource(R.string.similar_mix_info),
                        numbers = if (mix == null || !mix.compared) {
                            stringResource(R.string.similar_mix_none)
                        } else {
                            val lastFm = (mix.share * 100).roundToInt()
                            listOf(
                                stringResource(R.string.similar_mix_share, lastFm, 100 - lastFm),
                                stringResource(R.string.similar_mix_lastfm_line, mix.lastFm.per100, mix.lastFm.cards.roundToInt()),
                                stringResource(R.string.similar_mix_youtube_line, mix.youTube.per100, mix.youTube.cards.roundToInt()),
                                pluralStringResource(R.plurals.similar_mix_days, mix.days, mix.days),
                            ).joinToString("\n")
                        },
                        meaning = stringResource(R.string.similar_mix_meaning),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // What it learns from, and what it is told to leave out.
            ExplainedGroupTitle(
                title = stringResource(R.string.recommendations_learning_title),
                explanation = stringResource(R.string.recommendations_learning_title_info),
            )
            ExplainedSwitchPreference(
                title = stringResource(R.string.learn_from_listening),
                explanation = stringResource(R.string.learn_from_listening_info),
                description = stringResource(R.string.learn_from_listening_description),
                checked = learnFromListening,
                onCheckedChange = onLearnFromListeningChange,
            )
            ExplainedSwitchPreference(
                title = stringResource(R.string.rest_songs_i_skip),
                explanation = stringResource(R.string.rest_songs_i_skip_info),
                description = stringResource(R.string.rest_songs_i_skip_description),
                checked = restSongsISkip,
                onCheckedChange = onRestSongsISkipChange,
            )
            // Only means anything while rests are written, so it is only shown then.
            if (restSongsISkip) {
                ExplainedSwitchPreference(
                    title = stringResource(R.string.rests_everywhere),
                    explanation = stringResource(R.string.rests_everywhere_info),
                    description = stringResource(R.string.rests_everywhere_description),
                    checked = restsEverywhere,
                    onCheckedChange = onRestsEverywhereChange,
                )
            }
            ExplainedPreference(
                title = stringResource(R.string.exclusions),
                explanation = stringResource(R.string.exclusions_info),
                description = stringResource(R.string.exclusions_count, activeExclusions),
                onClick = { navController.navigate("settings/recommendations/exclusions") },
            )
            Spacer(Modifier.height(16.dp))
        }

        // The two pages behind this one: the numbers, and the buttons that change what it keeps.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            ExplainedPreference(
                title = stringResource(R.string.recommendations_doing_title),
                explanation = stringResource(R.string.recommendations_doing_info),
                description = stringResource(R.string.recommendations_doing_description),
                icon = { Icon(Icons.Rounded.Insights, null) },
                onClick = { navController.navigate("settings/recommendations/doing") },
            )
            if (Unreleased.ENGINE) {
                ExplainedPreference(
                    title = stringResource(R.string.recommendations_data_title),
                    explanation = stringResource(R.string.recommendations_data_info),
                    description = stringResource(R.string.recommendations_data_description),
                    icon = { Icon(Icons.Rounded.ManageHistory, null) },
                    onClick = { navController.navigate("settings/recommendations/data") },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    FloatingTopBar(title = stringResource(R.string.recommendations), navController = navController)
}
