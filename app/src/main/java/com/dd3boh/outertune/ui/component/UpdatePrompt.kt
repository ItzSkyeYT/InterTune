/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.UpdateChecker

/**
 * Asks whether to install an update, and shows what is in it.
 *
 * Shown whether or not automatic downloading is on, because Android will not install without a
 * confirmation from the user anyway. The only difference the setting makes is whether the apk is
 * already on the device when this appears, which is the difference between install being instant
 * and install having to fetch ten megabytes first.
 *
 * Three ways out on purpose. Cancel is "not this version, stop asking for now", later is "ask me
 * again in an hour", and install is the one that acts. Dismissing by tapping outside is the same as
 * later rather than the same as cancel, because a mis-tap should not silently bury an update.
 */
@Composable
fun UpdatePrompt(
    update: UpdateChecker.Update,
    /** True when the apk is already downloaded, so install will not have to wait. */
    ready: Boolean,
    /** True when Android has not yet been told this app may install apps. */
    needsPermission: Boolean,
    onInstall: () -> Unit,
    onRemindLater: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onRemindLater,
        title = { Text(stringResource(R.string.update_prompt_title, update.versionName)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        when {
                            needsPermission -> R.string.update_prompt_needs_permission
                            ready -> R.string.update_prompt_ready
                            else -> R.string.update_prompt_will_download
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (update.changelog.isNotBlank()) {
                    // Capped and scrollable: release notes run long, and a dialog that grows past
                    // the screen loses its buttons.
                    Text(
                        text = remember(update.changelog) { tidyChangelog(update.changelog) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(
                    stringResource(
                        if (needsPermission) R.string.update_prompt_allow
                        else R.string.update_prompt_install
                    )
                )
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onRemindLater) {
                    Text(stringResource(R.string.update_prompt_later))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/**
 * Makes GitHub release notes readable in a dialog.
 *
 * The notes are markdown written for a web page, so they arrive full of ## and ** which look like
 * debris on a phone. Rendering markdown properly would mean pulling in a renderer for one dialog,
 * so this just strips the syntax that actually shows up in these notes and leaves the words.
 */
private fun tidyChangelog(raw: String): String = raw
    .lineSequence()
    .map { line ->
        line.trim()
            .removePrefix("###").removePrefix("##").removePrefix("#")
            .replace("**", "")
            .replace(Regex("^[-*]\\s+"), "\u2022 ")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .trim()
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
