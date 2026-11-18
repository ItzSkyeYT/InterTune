/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.menu.SelectionMediaMetadataMenu

@Composable
fun RowScope.SelectHeader(
    navController: NavController,
    selectedItems: List<MediaMetadata>,
    totalItemCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    menuState: MenuState,
    onDismiss: () -> Unit = {},
    onRemoveFromHistory: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // As wide as its content: the pill the lists float over their foot, the queue's too. The queue's
    // Remove from queue is an item of the pill's menu and not a button beside the close (testers found it
    // "mysterious and dangerous" there; 15 Nov 2026). Undo after it stays as the queue offers it.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp, bottom = 3.dp)
    ) {
        Text(
            text = "${selectedItems.size}/${context.resources.getQuantityString(R.plurals.n_selected, totalItemCount, totalItemCount)}",
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f, false)
        )

        // option menu. Each button of the pill says what it does on a long press (LongPressLabel), by
        // the same name screen readers hear.
        LongPressLabel(stringResource(R.string.options)) {
            IconButton(
                onClick = {
                    menuState.show {
                        SelectionMediaMetadataMenu(
                            navController = navController,
                            selection = selectedItems,
                            onDismiss = menuState::dismiss,
                            clearAction = onDeselectAll,
                            onRemoveFromHistory = onRemoveFromHistory,
                            onRemoveFromPlaylist = onRemoveFromPlaylist,
                            onRemoveFromQueue = onRemoveFromQueue,
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.options),
                    tint = LocalContentColor.current
                )
            }
        }

        // select/deselect all
        val allSelected = selectedItems.size < totalItemCount

        LongPressLabel(stringResource(if (allSelected) R.string.select_all else R.string.deselect_all)) {
            IconButton(
                onClick = if (allSelected) onSelectAll else onDeselectAll
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Rounded.SelectAll else Icons.Rounded.Deselect,
                    // What a tap does: allSelected is true while some are still left out.
                    contentDescription = stringResource(if (allSelected) R.string.select_all else R.string.deselect_all),
                    tint = LocalContentColor.current
                )
            }
        }

        // close selection mode
        LongPressLabel(stringResource(R.string.cancel_selection)) {
            IconButton(
                onClick = onDismiss,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel_selection)
                )
            }
        }
    }
}
