/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.models.BlockedArtist
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.menu.ArtistPicture
import com.dd3boh.outertune.ui.menu.rememberArtistBlock
import com.dd3boh.outertune.utils.ArtistBlocking
import com.dd3boh.outertune.viewmodels.BlockedArtistsViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The most of YouTube's artists listed under the field: the first are the ones meant. */
private const val RESULTS_SHOWN = 8

/**
 * Settings > Library and content > Blocked artists (ArtistBlock): what blocking does in a line,
 * a field to add an artist by name from those YouTube finds, and the blocked artists, the latest
 * first, each with when and Unblock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedArtistsSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: BlockedArtistsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val block = rememberArtistBlock()
    val blocked = remember(block) { block.artists.sortedByDescending { it.at } }
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val answered by viewModel.answered.collectAsState()
    val undoLabel = stringResource(R.string.undo)
    // Read here, in the composition, so that it follows the app's language; filled in at the tap.
    val unblockedLine = stringResource(R.string.unblocked)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.blocked_artists_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.query.value = it },
            placeholder = { Text(stringResource(R.string.blocked_artists_add)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.query.value = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )

        // YouTube's artists for what is typed, but those already blocked, which are listed below.
        val found = results.filterNot { block.blocks(it.id, it.title) }.take(RESULTS_SHOWN)
        found.forEach { artist ->
            ArtistRow(
                thumbnail = artist.thumbnail,
                name = artist.title,
                line = null,
                action = stringResource(R.string.block_artist_confirm),
                // The whole row of a result, the room a finger needs; a blocked one only by its button.
                wholeRow = true,
                onAction = {
                    ArtistBlocking.block(context, BlockedArtist(artist.id, artist.title, artist.thumbnail))
                    viewModel.query.value = ""
                    focusManager.clearFocus()
                },
            )
        }
        if (!searching && found.isEmpty() && answered != null && answered == query && query.isNotBlank()) {
            Text(
                text = stringResource(R.string.blocked_artists_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
        if (found.isNotEmpty() && blocked.isNotEmpty()) Spacer(Modifier.height(16.dp))

        blocked.forEach { artist ->
            ArtistRow(
                thumbnail = artist.thumbnail,
                name = artist.name,
                line = blockedWhen(artist.at),
                action = stringResource(R.string.unblock),
                onAction = {
                    ArtistBlocking.unblock(context, artist)
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(unblockedLine.format(artist.name), actionLabel = undoLabel)
                        if (result == SnackbarResult.ActionPerformed) ArtistBlocking.block(context, artist)
                    }
                },
            )
        }
    }

    // At the bottom, over the player: by itself it stood over the page's first line.
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter),
        )
    }

    FloatingTopBar(
        title = stringResource(R.string.blocked_artists),
        navController = navController,
    )
}

/** One artist: the picture, the name and a line under it, and the one thing to do with it. */
@Composable
private fun ArtistRow(
    thumbnail: String?,
    name: String,
    line: String?,
    action: String,
    wholeRow: Boolean = false,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (wholeRow) Modifier.clickable(onClick = onAction) else Modifier)
            .padding(start = 8.dp),
    ) {
        ArtistPicture(thumbnail, Modifier.size(44.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** "Blocked today", or the day it was blocked, with its year when that was another year. */
@Composable
private fun blockedWhen(at: Long): String? {
    if (at <= 0L) return null
    val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    if (day == today) return stringResource(R.string.blocked_today)
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, if (day.year == today.year) "dMMM" else "dMMMyyyy")
    return stringResource(R.string.blocked_on, day.format(DateTimeFormatter.ofPattern(pattern, locale)))
}
