/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.MixPlaylistsKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.pill.MixRowLine
import com.dd3boh.outertune.mix.pill.PairPlanner
import com.dd3boh.outertune.mix.pill.Pill
import com.dd3boh.outertune.mix.pill.PillSong
import com.dd3boh.outertune.mix.pill.Pills
import com.dd3boh.outertune.mix.pill.PlainReason
import com.dd3boh.outertune.playback.automix.MixStores
import com.dd3boh.outertune.playback.automix.MixSwitches
import com.dd3boh.outertune.ui.component.ExplainButton
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the title column of a song row starts (ListItem in Items.kt: the row's 8 dp, the
 * thumbnail's 6 dp each side, the title's own 6 dp): a pill begins there, under the song it leaves.
 */
private val PillStart = 8.dp + 6.dp + ListThumbnailSize + 6.dp + 6.dp

/** A library song as a pill's side. Its length is in seconds, and 0 or less while not known. */
fun Song.pillSong() = PillSong(id, song.albumId ?: album?.id, if (song.duration > 0) song.duration * 1000L else -1)

/** Whether [playlistId]'s Mix switch is on. Off for every playlist until switched on. */
@Composable
fun rememberMixOn(playlistId: String?): Boolean {
    val on by rememberPreference(MixPlaylistsKey, defaultValue = emptySet())
    return MixSwitches.isOn(on, playlistId)
}

/**
 * What keeps every change plain right now, from the player and the settings (MusicService.mixBlocker).
 * Some of it moves with no event to follow (a sleep timer, casting), so it is looked at again every
 * two seconds while the page is shown; the page draws again only when the answer changes.
 */
@Composable
fun rememberMixBlocker(): PlainReason? {
    val playerConnection = LocalPlayerConnection.current ?: return null
    val repeat by playerConnection.repeatMode.collectAsState()
    var blocker by remember { mutableStateOf(playerConnection.service.mixBlocker()) }
    LaunchedEffect(repeat) {
        while (true) {
            blocker = playerConnection.service.mixBlocker()
            delay(2_000)
        }
    }
    return blocker
}

/** Every saved change, by its pair, read again whenever one is saved or forgotten. */
@Composable
fun rememberSavedTransitions(): Map<SongPair, TransitionSpec> {
    val context = LocalContext.current
    val changes by MixStores.changes.collectAsState()
    val saved by produceState(emptyMap<SongPair, TransitionSpec>(), changes) {
        value = withContext(Dispatchers.IO) {
            runCatching { MixStores.transitions(context).all().associateBy { it.pair } }.getOrDefault(emptyMap())
        }
    }
    return saved
}

/**
 * Auto for each pair. Nothing is analysed ahead yet (the analysis cache comes with the engine), so
 * every pair says Auto until it is; the real planner replaces this one in one place.
 */
val MixPlanner: PairPlanner = PairPlanner.NOT_YET

/**
 * The Mix row of a library playlist, under Play and Shuffle (DESIGN.md 2.4): the switch, and one
 * grey line under it saying what Mix is doing here. Only with Unreleased.AUTOMIX_EDITOR and two
 * songs or more, and off until it is switched on.
 */
@Composable
fun MixRow(
    playlistId: String,
    songCount: Int,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    if (!Unreleased.AUTOMIX_EDITOR || songCount < 2) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val on = rememberMixOn(playlistId)
    val playingHere = playerConnection.service.mixPlaylistId.collectAsState().value == playlistId
    val shuffled by playerConnection.shuffleModeEnabled.collectAsState()
    val blocker = rememberMixBlocker()
    val line = MixRowLine.of(on = on, shuffled = shuffled && playingHere, blocker = blocker)
    val title = stringResource(R.string.mix)

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                // The whole row is the switch, as in the settings; the (i) keeps its own touch.
                .toggleable(value = on, role = Role.Switch) { wanted ->
                    scope.launch(NonCancellable) { MixSwitches.set(context, playlistId, wanted) }
                }
                .padding(vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mix),
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(MixWords.rowLine(line)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExplainButton(title = title, body = stringResource(R.string.mix_explain))
            Spacer(Modifier.width(4.dp))
            Switch(checked = on, onCheckedChange = null)
        }
        val settings = (line as? MixRowLine.Blocked)?.let { MixWords.blockedSetting(it.reason) }
        if (settings != null) {
            TextButton(
                onClick = { navController.navigate(settings) },
                modifier = Modifier.padding(start = 36.dp),
            ) {
                Text(stringResource(R.string.settings))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The line under a song of a Mix playlist, naming the change into the next song (DESIGN.md 2.4,
 * 2.5). A tap opens the editor on that pair; a long press offers the editor, Back to Auto and a
 * plain change, headed by the two songs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixPillLine(
    from: PillSong,
    to: PillSong,
    fromTitle: String,
    toTitle: String,
    saved: TransitionSpec?,
    blocker: PlainReason?,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val pair = SongPair(from.id, to.id)
    val pill = Pills.of(from, to, saved, blocker, MixPlanner)
    val described = MixWords.described(pill, fromTitle, toTitle)
    var menu by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = PillStart),
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.mix_edit_transition),
                        onClick = { navController.openMixEditor(pair) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        },
                    )
                    .semantics { contentDescription = described }
                    .padding(horizontal = 10.dp),
            ) {
                if (pill.edited) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = MixWords.text(pill),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    text = stringResource(R.string.mix_pair, fromTitle, toTitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mix_edit_transition)) },
                    onClick = {
                        menu = false
                        navController.openMixEditor(pair)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mix_back_to_auto)) },
                    enabled = saved != null,
                    onClick = {
                        menu = false
                        scope.launch(Dispatchers.IO + NonCancellable) { MixStores.transitions(context).delete(pair) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mix_plain_for_these)) },
                    enabled = saved?.plain != true && from.id != to.id,
                    onClick = {
                        menu = false
                        scope.launch(Dispatchers.IO + NonCancellable) {
                            MixStores.transitions(context).put(Pills.plainSpec(pair, saved))
                        }
                    },
                )
            }
        }
    }
}
