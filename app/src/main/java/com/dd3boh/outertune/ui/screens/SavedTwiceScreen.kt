/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.savedtwice.SavedTwice
import com.dd3boh.outertune.savedtwice.SavedTwicePlace
import com.dd3boh.outertune.ui.component.FloatingTopBar
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.viewmodels.SavedTwiceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The songs saved more than once among [copies], counted off the main thread whenever one of [keys] changes. */
@Composable
fun rememberSavedTwiceCount(vararg keys: Any?, copies: () -> List<SavedTwice.Copy>): Int {
    val count by produceState(0, *keys) {
        value = withContext(Dispatchers.Default) { SavedTwice.songCount(SavedTwice.groups(copies())) }
    }
    return count
}

/**
 * The round button beside Play and Shuffle that opens Saved twice, with the number of songs saved
 * more than once on it. The list shows it only while that number is above nought.
 */
@Composable
fun SavedTwiceButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val said = pluralStringResource(R.plurals.saved_twice_button, count, count)
    BadgedBox(
        badge = {
            // The number is in the button's own words for a screen reader, so not said twice.
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.clearAndSetSemantics {},
            ) { Text(count.toString()) }
        },
        modifier = modifier,
    ) {
        OutlinedIconButton(
            onClick = onClick,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.semantics { contentDescription = said },
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** The words that name the list the screen was opened from, each with the count it carries. */
private class PlaceWords(private val place: SavedTwicePlace, private val name: String) {
    fun intro(res: Resources, n: Int): String = when (place) {
        SavedTwicePlace.Liked -> res.getQuantityString(R.plurals.saved_twice_intro_liked, n, n)
        SavedTwicePlace.Library -> res.getQuantityString(R.plurals.saved_twice_intro_library, n, n)
        is SavedTwicePlace.Playlist -> res.getQuantityString(R.plurals.saved_twice_intro_playlist, n, n, name)
    }

    fun out(res: Resources): String = when (place) {
        SavedTwicePlace.Liked -> res.getString(R.string.saved_twice_out_liked)
        SavedTwicePlace.Library -> res.getString(R.string.saved_twice_out_library)
        is SavedTwicePlace.Playlist -> res.getString(R.string.saved_twice_out_playlist, name)
    }

    fun take(res: Resources, n: Int): String = when (place) {
        SavedTwicePlace.Liked -> res.getQuantityString(R.plurals.saved_twice_take_liked, n, n)
        SavedTwicePlace.Library -> res.getQuantityString(R.plurals.saved_twice_take_library, n, n)
        is SavedTwicePlace.Playlist -> res.getQuantityString(R.plurals.saved_twice_take_playlist, n, n, name)
    }

    fun done(res: Resources, n: Int): String = when (place) {
        SavedTwicePlace.Liked -> res.getQuantityString(R.plurals.saved_twice_done_liked, n, n)
        SavedTwicePlace.Library -> res.getQuantityString(R.plurals.saved_twice_done_library, n, n)
        is SavedTwicePlace.Playlist -> res.getQuantityString(R.plurals.saved_twice_done_playlist, n, n, name)
    }
}

/** The first line of a copy's row: what it is, the album when known, its length. "Song · Riot! · 3:31". */
private fun copyLine(res: Resources, copy: SavedTwice.Copy): String {
    val kind = res.getString(
        when (copy.kind) {
            SavedTwice.Kind.SONG -> R.string.saved_twice_song
            SavedTwice.Kind.MUSIC_VIDEO -> R.string.saved_twice_music_video
            SavedTwice.Kind.FILE -> R.string.saved_twice_file
        }
    )
    val album = copy.album?.takeIf { it.isNotBlank() }?.let {
        if (SavedTwice.isSingle(copy)) res.getString(R.string.saved_twice_single, it) else it
    }
    val length = if (copy.duration > 0) makeTimeString(copy.duration * 1000L) else null
    val line = listOfNotNull(kind, album).joinToString(" · ")
    // The length keeps to the word before it, so a long album breaks its own name over two lines
    // rather than leaving the length alone on the second.
    return if (length == null) line else "$line\u00A0·\u00A0$length"
}

/**
 * Saved twice: each song of one list that is in it more than once, with its copies, one picked to
 * keep, and one button that takes the others out. The list it came from shows what is left, with
 * Undo, since that is where somebody looks next.
 *
 * [appScope] outlives this screen, which closes at the tap: the take out and its Undo run there.
 */
@Composable
fun SavedTwiceScreen(
    navController: NavController,
    appScope: CoroutineScope,
    viewModel: SavedTwiceViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val snackbarHostState = LocalSnackbarHostState.current
    val shown by viewModel.shown.collectAsState()
    val picks by viewModel.picks.collectAsState()
    val place = viewModel.place ?: return
    val words = remember(place, shown.placeName) { PlaceWords(place, shown.placeName.orEmpty()) }
    val res = context.resources
    var taking by remember { mutableStateOf(false) }

    val toTakeOut = remember(shown, picks) { SavedTwice.toTakeOut(shown.groups, picks).size }
    val coverPx = with(density) { 40.dp.toPx() }.roundToInt()

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (shown.loaded) {
            item(key = "intro") {
                Text(
                    text = if (shown.groups.isEmpty()) stringResource(R.string.saved_twice_none) else words.intro(res, shown.groups.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                )
            }
        }

        items(shown.groups, key = { it.id }) { group ->
            val kept = viewModel.kept(group, picks)
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val head = group.keptByDefault
                Text(
                    text = listOf(head.title, head.artists.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                group.copies.forEach { copy ->
                    CopyRow(
                        song = shown.songs[copy.key],
                        line = copyLine(res, copy),
                        fate = if (copy.key == kept) stringResource(R.string.saved_twice_keep) else words.out(res),
                        picked = copy.key == kept,
                        coverPx = coverPx,
                        onPick = { viewModel.pick(group, copy) },
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
        }

        if (shown.groups.isNotEmpty()) {
            item(key = "take") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                ) {
                    Button(
                        onClick = {
                            if (taking) return@Button
                            val shelf = viewModel.shelf() ?: return@Button
                            taking = true
                            val copies = viewModel.toTakeOut()
                            val undo = res.getString(R.string.undo)
                            navController.navigateUp()
                            appScope.launch {
                                val taken = shelf.takeOut(copies)
                                if (taken.count == 0) return@launch
                                val result = snackbarHostState.showSnackbar(
                                    message = words.done(res, taken.count),
                                    actionLabel = undo,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) taken.putBack()
                            }
                        },
                        enabled = toTakeOut > 0 && !taking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(words.take(res, toTakeOut), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        text = stringResource(R.string.saved_twice_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    FloatingTopBar(title = stringResource(R.string.saved_twice), navController = navController)
}

/** One copy of a song: its radio button, a small cover, what it is, and whether it stays. */
@Composable
private fun CopyRow(
    song: Song?,
    line: String,
    fate: String,
    picked: Boolean,
    coverPx: Int,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        RadioButton(selected = picked, onClick = null, modifier = Modifier.size(48.dp))
        AsyncImage(
            model = song?.song?.getThumbnailModel(coverPx, coverPx),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(ThumbnailCornerRadius)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
