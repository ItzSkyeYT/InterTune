/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RegionNoteDoneKey
import com.dd3boh.outertune.utils.YouTubeRegion
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Why songs, albums and mixes are missing where YouTube Music has no free plan (#68): YouTube
 * shows them only to Premium members there, by the connection, so the way back is Premium or a
 * connection through another country. Proxy settings opens the page with the app's own proxy.
 *
 * The shape and colour of [AutoBlockerNote], since it is the same kind of thing: a word from the
 * app itself, with its buttons under the words so they wrap in a large font or a long language.
 * [onDone] null leaves Done out, for the album page, where the note is that page's answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionNote(
    onProxy: () -> Unit,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.region_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard,
                    // The first line is level with the middle of the picture beside it.
                    modifier = Modifier.padding(top = 10.dp, end = 6.dp),
                )
                Text(
                    text = stringResource(R.string.region_note_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onCard,
                    modifier = Modifier.padding(top = 4.dp, end = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onProxy,
                        colors = ButtonDefaults.textButtonColors(contentColor = onCard),
                    ) {
                        Text(stringResource(R.string.region_note_proxy))
                    }
                    if (onDone != null) {
                        TextButton(
                            onClick = onDone,
                            colors = ButtonDefaults.textButtonColors(contentColor = onCard),
                        ) {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                }
            }
        }
    }
}

/**
 * [RegionNote] on Home and above search results, while YouTube sees the connection in a country
 * with no free plan and until Done is tapped there. Done is kept with the country it was tapped
 * for, so the note comes back only for another such country.
 */
@Composable
fun RegionNoteIfClosed(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val seen by YouTubeRegion.seen.collectAsState()
    var done by rememberPreference(RegionNoteDoneKey, defaultValue = "")
    if (!YouTubeRegion.closed(seen) || done == seen) return
    RegionNote(
        onProxy = { navController.navigate("settings/privacy") },
        onDone = { done = seen.orEmpty() },
        modifier = modifier,
    )
}
