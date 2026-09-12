/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.dialog.DefaultDialog

/**
 * The little "i" beside a setting, and the dialog behind it.
 *
 * A recommendation engine that cannot say what it is doing is not one anybody should be asked to
 * trust, and a one line description under a switch has room for the what but never for the how.
 * So every item the engine touches carries one of these: the row's own line stays short, and the
 * mechanism, the data it reads and the honest caveats live here, where they cost nothing to
 * anyone who is not asking.
 *
 * The button is its own touch target, so it opens the explanation without toggling the switch or
 * following the row it sits on.
 */
@Composable
fun ExplainButton(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    if (open) {
        ExplainDialog(title = title, body = body, onDismiss = { open = false })
    }

    IconButton(
        onClick = { open = true },
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.explain_what_this_does, title),
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ExplainDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        title = { Text(title) },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.explain_close)) }
        },
    ) {
        // Capped and scrollable rather than trusted to fit: the longest of these runs to a
        // paragraph and a half, and a short phone in a large font would otherwise push the
        // button off the bottom of the screen.
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        )
    }
}

/** A settings row that does something, or only shows something, with its explanation beside it. */
@Composable
fun ExplainedPreference(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    isEnabled: Boolean = true,
) = PreferenceEntry(
    modifier = modifier,
    title = { Text(title) },
    description = description,
    trailingContent = { ExplainButton(title = title, body = explanation) },
    onClick = onClick,
    isEnabled = isEnabled,
)

/** A switch with its explanation between the text and the switch itself. */
@Composable
fun ExplainedSwitchPreference(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isEnabled: Boolean = true,
) = PreferenceEntry(
    modifier = modifier,
    title = { Text(title) },
    description = description,
    trailingContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplainButton(title = title, body = explanation)

            Spacer(Modifier.width(4.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = isEnabled,
            )
        }
    },
    onClick = { onCheckedChange(!checked) },
    isEnabled = isEnabled,
)

/**
 * A section heading with an explanation of the section as a whole.
 *
 * The heading is drawn exactly as [PreferenceGroupTitle] draws it; the dialog gets the heading in
 * its own case rather than the shouted one.
 */
@Composable
fun ExplainedGroupTitle(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.fillMaxWidth(),
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp),
    )

    ExplainButton(title = title, body = explanation)
}
