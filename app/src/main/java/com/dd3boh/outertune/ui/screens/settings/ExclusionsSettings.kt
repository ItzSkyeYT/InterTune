/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.viewmodels.ExclusionsViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything the recommendation rows have been told to leave out, each with why and until when.
 * Exclusions touch recommendation rows only: search, the library, playlists and radio are never
 * filtered, and banning a song covers its other versions.
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
    val active = exclusions.filter { it.expiresAt == null || it.expiresAt > now }
    val expired = exclusions - active.toSet()
    val undoLabel = stringResource(R.string.undo)
    val liftedMessage = stringResource(R.string.exclusion_lifted)

    @Composable
    fun reasonText(e: RecommendationExclusion): String = when (e.reason) {
        ExclusionsViewModel.REASON_BAN -> stringResource(if (e.kind == ExclusionsViewModel.KIND_SONG) R.string.exclusion_reason_song_ban else R.string.exclusion_reason_artist_ban)
        ExclusionsViewModel.REASON_SNOOZE -> stringResource(R.string.exclusion_reason_snooze)
        else -> stringResource(R.string.exclusion_reason_rest)
    }

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

    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()))

    TopAppBar(
        title = { Text(stringResource(R.string.exclusions)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior
    )
}
