/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.MediaControlButtonsKey
import com.dd3boh.outertune.playback.MediaControlButton
import com.dd3boh.outertune.playback.MediaControlButtons
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.tourTarget

/**
 * Which two buttons the phone's media controls show beside previous, play and next: the
 * notification, the lock screen and One UI's media panel.
 *
 * Picked in order by tapping, so the dialog is one list rather than a list per slot, and the
 * number on each row is where it will sit. The service follows the setting and redraws the
 * controls as soon as it changes.
 */
@Composable
fun ColumnScope.MediaControlButtonsFrag() {
    val (saved, onSavedChange) = rememberPreference(MediaControlButtonsKey, defaultValue = "")
    val chosen = remember(saved) { MediaControlButtons.parse(saved) }
    var showDialog by remember { mutableStateOf(false) }

    PreferenceEntry(
        modifier = Modifier.tourTarget(Tour.SETTING_MEDIA_BUTTONS),
        title = { Text(stringResource(R.string.media_control_buttons)) },
        description = chosen.map { mediaControlLabel(it) }.joinToString(", "),
        icon = { Icon(Icons.Rounded.ScreenLockPortrait, null) },
        onClick = { showDialog = true }
    )

    if (showDialog) {
        // Starts from what is saved every time it opens, and saves nothing until OK.
        var picked by remember { mutableStateOf(chosen) }
        ActionPromptDialog(
            title = stringResource(R.string.media_control_buttons),
            onDismiss = { showDialog = false },
            onConfirm = {
                showDialog = false
                onSavedChange(MediaControlButtons.serialize(picked))
            },
            onReset = { picked = MediaControlButtons.DEFAULT },
            onCancel = { showDialog = false },
            isInputValid = picked.size == MediaControlButtons.SLOTS,
        ) {
            Text(
                text = stringResource(R.string.media_control_buttons_explain),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            MediaControlButton.entries.forEach { button ->
                val slot = picked.indexOf(button)
                val canTap = slot >= 0 || picked.size < MediaControlButtons.SLOTS
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = slot >= 0,
                            enabled = canTap,
                            role = Role.Checkbox,
                            onValueChange = { picked = MediaControlButtons.toggle(picked, button) }
                        )
                        .alpha(if (canTap) 1f else 0.5f)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Icon(mediaControlIcon(button), null)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = mediaControlLabel(button),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    SlotBadge(slot)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The position a picked button will take, or an empty ring for one that is not picked. */
@Composable
private fun SlotBadge(slot: Int) {
    val shape = CircleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .then(
                if (slot >= 0) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, shape)
            )
    ) {
        if (slot >= 0) {
            Text(
                text = (slot + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun mediaControlLabel(button: MediaControlButton): String = stringResource(
    when (button) {
        MediaControlButton.LIKE -> R.string.action_like
        MediaControlButton.RADIO -> R.string.start_radio
        MediaControlButton.SHUFFLE -> R.string.shuffle
        MediaControlButton.REPEAT -> R.string.media_control_repeat
        MediaControlButton.LIBRARY -> R.string.add_to_library
    }
)

private fun mediaControlIcon(button: MediaControlButton): ImageVector = when (button) {
    MediaControlButton.LIKE -> Icons.Rounded.FavoriteBorder
    MediaControlButton.RADIO -> Icons.Rounded.Radio
    MediaControlButton.SHUFFLE -> Icons.Rounded.Shuffle
    MediaControlButton.REPEAT -> Icons.Rounded.Repeat
    MediaControlButton.LIBRARY -> Icons.Rounded.LibraryAdd
}
