/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.pill.PillSong
import com.dd3boh.outertune.mix.pill.Pills
import com.dd3boh.outertune.playback.automix.MixStores
import com.dd3boh.outertune.ui.component.FloatingTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Stands in for the editor until it lands, so every way in (a pill, the queue's line, the
 * player's menu) can be followed to a page: it names the pair, says what its pill says, and offers
 * the two choices the long press has. The editor replaces this composable under the same name and
 * route (MixRoutes.EDITOR).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MixEditorScreen(navController: NavController, pair: SongPair) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val from by remember(pair.from) { database.song(pair.from) }.collectAsState(initial = null)
    val to by remember(pair.to) { database.song(pair.to) }.collectAsState(initial = null)
    val saved = rememberSavedTransitions()[pair]
    val blocker = rememberMixBlocker()
    val fromSong = from?.pillSong() ?: PillSong(pair.from, null, -1)
    val toSong = to?.pillSong() ?: PillSong(pair.to, null, -1)
    val pill = Pills.of(fromSong, toSong, saved, blocker, MixPlanner)

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .padding(horizontal = 16.dp, vertical = 72.dp),
    ) {
        Text(
            text = stringResource(R.string.mix_pair, from?.title ?: pair.from, to?.title ?: pair.to),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(MixWords.text(pill), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.mix_editor_stub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = saved != null,
                onClick = { scope.launch(Dispatchers.IO + NonCancellable) { MixStores.transitions(context).delete(pair) } },
            ) { Text(stringResource(R.string.mix_back_to_auto)) }
            TextButton(
                enabled = saved?.plain != true && pair.from != pair.to,
                onClick = { scope.launch(Dispatchers.IO + NonCancellable) { MixStores.transitions(context).put(Pills.plainSpec(pair, saved)) } },
            ) { Text(stringResource(R.string.mix_plain_for_these)) }
        }
    }

    FloatingTopBar(title = stringResource(R.string.mix_edit_transition), navController = navController)
}
