/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.migration.ImportParse
import com.dd3boh.outertune.migration.ImportRun
import com.dd3boh.outertune.migration.LibraryImport
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.viewmodels.LibraryImportViewModel
import com.zionhuang.innertube.models.SongItem

/**
 * Import from another service: pick an export, watch it matched, check what it was unsure of,
 * then make the playlists.
 *
 * One screen through every stage, because the work lives in [LibraryImport] rather than here: the
 * screen only shows where that is, so leaving and coming back lands on the same place.
 */
@Composable
fun LibraryImportScreen(
    navController: NavController,
    viewModel: LibraryImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val session = viewModel.session

    // Providers disagree about what a CSV is called, so the list is wide, and anything that is
    // not an export gets a plain answer from the parser rather than a greyed out file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) session.open(uri)
    }
    val choose = { picker.launch(CSV_TYPES) }

    val progress = state.progress
    val stage = state.stage
    // Folded away at first: they went in without asking, and most people will not want to look.
    var showMatched by rememberSaveable { mutableStateOf(false) }
    // Start over sits beside Create playlists, and a slip there would throw away minutes of
    // searching and every pick, so once anything has been looked up it asks first.
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    val startOver: () -> Unit = {
        if ((progress?.checked ?: 0) > 0) {
            confirmStartOver = true
        } else {
            session.reset()
        }
    }
    val started = stage !in listOf(LibraryImport.Stage.IDLE, LibraryImport.Stage.READING, LibraryImport.Stage.FAILED)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            if (!started) {
                item(key = "intro") { Intro() }
                if (stage == LibraryImport.Stage.FAILED) {
                    item(key = "error") { Problem(state) }
                }
                item(key = "choose") {
                    if (stage == LibraryImport.Stage.READING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.import_library_reading))
                        }
                    } else {
                        Button(onClick = choose, modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.import_library_choose))
                        }
                    }
                }
                return@LazyColumn
            }

            item(key = "summary") {
                Summary(
                    state = state,
                    onCancel = session::cancel,
                    onResume = session::resume,
                    onStartOver = startOver,
                    onCreate = session::create,
                    onDone = {
                        session.reset()
                        navController.navigateUp()
                    },
                    onAnother = {
                        session.reset()
                        choose()
                    },
                )
            }

            if (progress == null || stage == LibraryImport.Stage.CREATED) return@LazyColumn

            if (progress.review.isNotEmpty()) {
                item(key = "review-title") {
                    Column {
                        PreferenceGroupTitle(
                            title = stringResource(R.string.import_library_review_title, progress.review.size)
                        )
                        Text(
                            text = stringResource(R.string.import_library_review_help),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                }
                items(progress.review, key = { "review-${it.index}" }) { item ->
                    Review(
                        item = item,
                        onPick = { song -> session.pick(item.index, song) },
                        onSkip = { session.skip(item.index) },
                        onUndo = { session.undecide(item.index) },
                    )
                }
            }

            if (progress.notFound.isNotEmpty()) {
                item(key = "missing-title") {
                    Column {
                        PreferenceGroupTitle(
                            title = stringResource(R.string.import_library_missing_title, progress.notFound.size)
                        )
                        Text(
                            text = stringResource(R.string.import_library_missing_help),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                }
                items(progress.notFound.size, key = { "missing-$it" }) { i ->
                    val track = progress.notFound[i]
                    Text(
                        text = joinByBullet(track.title, track.artist.ifEmpty { null }),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            if (progress.matchedItems.isNotEmpty()) {
                item(key = "matched-title") {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PreferenceGroupTitle(
                                title = stringResource(R.string.import_library_matched_title, progress.matched),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showMatched = !showMatched }) {
                                Text(stringResource(if (showMatched) R.string.import_library_hide else R.string.import_library_show))
                            }
                        }
                        Text(
                            text = stringResource(R.string.import_library_matched_help),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                }
                if (showMatched) {
                    items(progress.matchedItems, key = { "matched-${it.index}" }) { item ->
                        Matched(
                            item = item,
                            onLeaveOut = { session.skip(item.index) },
                            onUndo = { session.undecide(item.index) },
                        )
                    }
                }
            }

            // The same button again at the end of a long review, where the thumb already is.
            if (stage == LibraryImport.Stage.MATCHED && progress.review.isNotEmpty()) {
                item(key = "create-bottom") {
                    Button(
                        onClick = session::create,
                        modifier = Modifier.padding(16.dp),
                    ) { Text(stringResource(R.string.import_library_create)) }
                }
            }
        }
    }

    if (confirmStartOver) {
        ActionPromptDialog(
            title = stringResource(R.string.import_library_start_over_title),
            onDismiss = { confirmStartOver = false },
            onCancel = { confirmStartOver = false },
            onConfirm = {
                confirmStartOver = false
                session.reset()
            },
        ) {
            Text(
                text = stringResource(R.string.import_library_start_over_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    FloatingTopBar(title = stringResource(R.string.import_library_title), navController = navController)
}

private val CSV_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/csv",
    "application/vnd.ms-excel",
    "application/octet-stream",
    // Apple Music's library export.
    "text/xml",
    "application/xml",
    // Exportify's Export All.
    "application/zip",
    "application/x-zip-compressed",
)

@Composable
private fun Intro() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.import_library_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.import_library_spotify), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.import_library_others), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.import_library_review_note), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_library_premium),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Problem(state: LibraryImport.State) {
    val message = when (state.problem) {
        null -> stringResource(R.string.import_library_error_unreadable)
        ImportParse.Problem.EMPTY -> stringResource(R.string.import_library_error_empty)
        ImportParse.Problem.NOT_TEXT -> stringResource(R.string.import_library_error_not_text)
        ImportParse.Problem.NO_TITLE_COLUMN ->
            stringResource(R.string.import_library_error_no_title, state.detail.orEmpty())
        ImportParse.Problem.NO_TRACKS -> stringResource(R.string.import_library_error_no_tracks)
        ImportParse.Problem.NOT_SONGS -> stringResource(R.string.import_library_error_not_songs)
    }
    ElevatedCard(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column {
                state.fileName?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(
    state: LibraryImport.State,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onCreate: () -> Unit,
    onDone: () -> Unit,
    onAnother: () -> Unit,
) {
    val progress = state.progress
    ElevatedCard(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.fileName.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val songs = progress?.total ?: 0
            Text(
                text = joinByBullet(
                    state.format?.label,
                    stringResource(
                        R.string.import_library_contents,
                        pluralStringResource(R.plurals.import_library_songs, songs, songs),
                        pluralStringResource(R.plurals.import_library_playlists, state.playlists, state.playlists),
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.skippedRows > 0) {
                Text(
                    text = pluralStringResource(R.plurals.import_library_skipped_rows, state.skippedRows, state.skippedRows),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.stage == LibraryImport.Stage.CREATED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.import_library_created,
                        pluralStringResource(R.plurals.import_library_playlists, state.createdPlaylists, state.createdPlaylists),
                        pluralStringResource(R.plurals.import_library_songs, state.createdSongs, state.createdSongs),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = onDone) { Text(stringResource(R.string.import_library_done)) }
                    OutlinedButton(onClick = onAnother) { Text(stringResource(R.string.import_library_another)) }
                }
            } else {
                Progress(state, onCancel, onResume, onStartOver, onCreate)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Progress(
    state: LibraryImport.State,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onCreate: () -> Unit,
) {
    val progress = state.progress
    Column {
        if (progress != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 1f else progress.checked.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.import_library_checked, progress.checked, progress.total),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Count(stringResource(R.string.import_library_matched), progress.matched)
            Count(stringResource(R.string.import_library_to_review), progress.review.size)
            Count(stringResource(R.string.import_library_not_found), progress.notFound.size)
        }

        when (state.stage) {
            LibraryImport.Stage.STOPPED -> Note(stringResource(R.string.import_library_stopped))
            LibraryImport.Stage.OFFLINE -> Note(stringResource(R.string.import_library_offline))
            LibraryImport.Stage.MATCHED -> {
                val lists = progress?.kept ?: 0
                Note(pluralStringResource(R.plurals.import_library_ready, lists, lists))
                if (progress != null && progress.undecided > 0) {
                    Note(pluralStringResource(R.plurals.import_library_undecided, progress.undecided, progress.undecided))
                }
            }
            else -> Unit
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            when (state.stage) {
                LibraryImport.Stage.MATCHING ->
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.import_library_cancel)) }
                LibraryImport.Stage.STOPPED, LibraryImport.Stage.OFFLINE -> {
                    Button(onClick = onResume) { Text(stringResource(R.string.import_library_continue)) }
                    OutlinedButton(onClick = onStartOver) { Text(stringResource(R.string.import_library_start_over)) }
                }
                LibraryImport.Stage.MATCHED -> {
                    Button(
                        onClick = onCreate,
                        enabled = progress != null && progress.kept > 0,
                    ) { Text(stringResource(R.string.import_library_create)) }
                    OutlinedButton(onClick = onStartOver) { Text(stringResource(R.string.import_library_start_over)) }
                }
                LibraryImport.Stage.CREATING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.import_library_creating))
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Count(label: String, count: Int) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One track the matcher was unsure of: what the file said, then YouTube's best few. A tap on one
 * picks it and a second tap takes that back; Skip leaves the track out.
 */
@Composable
private fun Review(
    item: ImportRun.ReviewItem,
    onPick: (SongItem) -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
) {
    val picked = (item.choice as? ImportRun.Choice.Picked)?.song?.id
    val skipped = item.choice is ImportRun.Choice.Skipped
    ElevatedCard(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = item.track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = joinByBullet(
                    item.track.artist.ifEmpty { null },
                    item.track.durationSeconds?.let { makeTimeString(it * 1000L) },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (!skipped) {
                Spacer(Modifier.height(4.dp))
                item.candidates.forEach { scored ->
                    val song = scored.candidate
                    val chosen = song.id == picked
                    YouTubeListItem(
                        item = song,
                        isSelected = chosen,
                        // No library badges: each would be a database query per row, for a list
                        // that is about YouTube's catalogue rather than this phone's.
                        badges = {},
                        trailingContent = {
                            if (chosen) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.import_library_picked),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        },
                        modifier = Modifier.clickable { if (chosen) onUndo() else onPick(song) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                if (skipped) {
                    TextButton(onClick = onUndo) { Text(stringResource(R.string.import_library_skipped)) }
                } else {
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.import_library_skip)) }
                }
            }
        }
    }
}

/**
 * One track that went in without asking: what the file said, then what it became. Leave out takes
 * it out of every playlist it would have gone into, for the few a match gets wrong.
 */
@Composable
private fun Matched(
    item: ImportRun.MatchedItem,
    onLeaveOut: () -> Unit,
    onUndo: () -> Unit,
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .alpha(if (item.leftOut) 0.5f else 1f)
        .padding(vertical = 4.dp)) {
        Text(
            text = stringResource(
                R.string.import_library_from_file,
                joinByBullet(
                    item.track.title,
                    item.track.artist.ifEmpty { null },
                    item.track.durationSeconds?.let { makeTimeString(it * 1000L) },
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        YouTubeListItem(
            item = item.best.candidate,
            badges = {},
            trailingContent = {
                if (item.leftOut) {
                    TextButton(onClick = onUndo) { Text(stringResource(R.string.import_library_undo)) }
                } else {
                    TextButton(onClick = onLeaveOut) { Text(stringResource(R.string.import_library_leave_out)) }
                }
            },
        )
    }
}
