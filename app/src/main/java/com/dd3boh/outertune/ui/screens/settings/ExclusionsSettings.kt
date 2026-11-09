/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import com.dd3boh.outertune.ui.component.FloatingTopBar

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything the recommendation rows have been told to leave out, each with why and until when.
 * Exclusions touch recommendation rows only: search, the library, playlists and radio are never
 * filtered, and banning a song covers its other versions. A song the listener chose not to play
 * (DontPlay) is listed here too, with Lift, and one line under the promise says what it does
 * beyond the rows while one is in force.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExclusionsSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ExclusionsViewModel = hiltViewModel(),
) {
    val exclusions by viewModel.exclusions.collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val active = exclusions.filter { exclusionInForce(it, now) }
    val expired = exclusions - active.toSet()
    val undoLabel = stringResource(R.string.undo)
    val liftedMessage = stringResource(R.string.exclusion_lifted)

    @Composable
    fun reasonText(e: RecommendationExclusion): String = stringResource(exclusionReason(e))

    @Composable
    fun row(e: RecommendationExclusion) {
        val until = e.expiresAt?.let { stringResource(R.string.exclusion_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
        PreferenceEntry(
            title = { Text(e.label) },
            description = listOfNotNull(reasonText(e), until).joinToString(" · "),
            trailingContent = {
                TextButton(onClick = {
                    viewModel.lift(e)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(liftedMessage, actionLabel = undoLabel)
                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(e)
                    }
                }) { Text(stringResource(R.string.exclusion_lift)) }
            },
            onClick = null,
        )
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.exclusions_promise),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (showsDontPlayNote(active)) {
            Text(
                text = stringResource(R.string.exclusions_dont_play_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
        PreferenceGroupTitle(title = stringResource(R.string.exclusions_active_title))
        if (active.isEmpty()) {
            Text(
                text = stringResource(R.string.exclusions_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        active.forEach { row(it) }
        if (expired.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PreferenceGroupTitle(title = stringResource(R.string.exclusions_expired_title))
            expired.forEach { row(it) }
        }
        Spacer(Modifier.height(24.dp))
    }

    // At the bottom, over the player, as on every other screen: by itself it stood over the page's first line.
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter),
        )
    }

    FloatingTopBar(
        title = stringResource(R.string.exclusions),
        navController = navController,
    )
}

/** Whether [e] is in force at [now]: one with no end, Don't play this song among them, until lifted. */
fun exclusionInForce(e: RecommendationExclusion, now: Long): Boolean = e.expiresAt == null || e.expiresAt > now

/**
 * The words for why [e] is there. Don't play this song is told as itself only in a build with it
 * (Unreleased.ARTIST_BLOCK): without, a row of it from a restored backup keeps the song out of the
 * recommendation rows alone, which is Not this song.
 */
@StringRes
fun exclusionReason(e: RecommendationExclusion, dontPlayOn: Boolean = Unreleased.ARTIST_BLOCK): Int = when (e.reason) {
    ExclusionsViewModel.REASON_BAN -> if (e.kind == ExclusionsViewModel.KIND_SONG) R.string.exclusion_reason_song_ban else R.string.exclusion_reason_artist_ban
    ExclusionsViewModel.REASON_SNOOZE -> R.string.exclusion_reason_snooze
    ExclusionsViewModel.REASON_GONE -> R.string.exclusion_reason_gone
    ExclusionsViewModel.REASON_DONT_PLAY -> if (dontPlayOn) R.string.exclusion_reason_dont_play else R.string.exclusion_reason_song_ban
    else -> R.string.exclusion_reason_rest
}

/** Whether the page says what Don't play this song does beyond the rows: with the feature, and one in force. */
fun showsDontPlayNote(active: List<RecommendationExclusion>, dontPlayOn: Boolean = Unreleased.ARTIST_BLOCK): Boolean =
    dontPlayOn && active.any { it.reason == ExclusionsViewModel.REASON_DONT_PLAY }
