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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R

/**
 * The line at the top of Home for somebody whose update Auto Blocker stopped, once the update has
 * gone through: Auto Blocker was turned off for it, and can be turned back on now
 * (AutoBlockerReturn). It is said once, and nothing has to be answered.
 *
 * The shape of the welcome back card ([com.dd3boh.outertune.ui.screens.walkthrough.WelcomeBanner])
 * and the colour of it, since it is the same kind of thing: a word from the app itself after an
 * update, which the welcome back card, the announcement and the question are told apart from. It
 * has two buttons where the others have one, so the buttons go under the words and wrap to a
 * second line when a large font or French is too wide for one. Open Auto Blocker takes the person
 * to its page, and Done sends the note away; neither needs the other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoBlockerNote(
    onOpen: () -> Unit,
    onDone: () -> Unit,
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
                    imageVector = Icons.Rounded.Shield,
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
                    text = stringResource(R.string.auto_blocker_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onCard,
                    // The first line is level with the middle of the picture beside it.
                    modifier = Modifier.padding(top = 10.dp, end = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onOpen,
                        colors = ButtonDefaults.textButtonColors(contentColor = onCard),
                    ) {
                        Text(stringResource(R.string.update_open_auto_blocker))
                    }
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
