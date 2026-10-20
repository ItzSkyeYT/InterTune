/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.Links
import com.dd3boh.outertune.utils.Donors

/**
 * About > Support InterTune: a line on how to give, which is a direct message on the Discord
 * server (decided on 17 Oct 2026, the day the first donation came that way), and thanks to those
 * who have, each name on a line of its own. The names come from README > Donors ([Donors]): the
 * last list shows at once, and the README is asked again while the note is open, so a donor added
 * there appears without an update. The text scrolls, so a long list never pushes the buttons off
 * a short screen.
 */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val donors by produceState(initialValue = emptyList<String>()) {
        value = Donors.cached(context)
        value = Donors.refresh(context)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.VolunteerActivism, contentDescription = null) },
        title = { Text(stringResource(R.string.support_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.support_text), style = MaterialTheme.typography.bodyMedium)
                if (donors.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.support_thanks),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    donors.forEach { name ->
                        Row(Modifier.padding(start = 4.dp, top = 6.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                uriHandler.openUri(Links.DISCORD)
                onDismiss()
            }) { Text(stringResource(R.string.support_discord)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
