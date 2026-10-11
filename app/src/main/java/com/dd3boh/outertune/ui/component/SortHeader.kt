/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaylistSongSortType
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.utils.shrinkToFit

/**
 * What a list is sorted by, and which way: the name opens the choices, the arrow turns the order.
 *
 * In a row that has more after it (a count, the three dots), give it a weight. What comes after
 * then keeps its size and its place, and the name has what is left.
 */
@Composable
inline fun <reified T : Enum<T>> SortHeader(
    sortType: T,
    sortDescending: Boolean,
    crossinline onSortTypeChange: (T) -> Unit,
    crossinline onSortDescendingChange: (Boolean) -> Unit,
    crossinline sortTypeText: (T) -> Int,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        // One line, in what the arrow leaves of the row, and no wider than its words. A long
        // name gets smaller before it is cut: in Russian "Количество воспроизведений" took the
        // whole row of the Songs tab, the count beside it went on two lines and the three dots
        // after the count were not drawn at all.
        val labelStyle = MaterialTheme.typography.labelLarge
        Text(
            text = stringResource(sortTypeText(sortType)),
            color = MaterialTheme.colorScheme.primary,
            style = labelStyle,
            autoSize = labelStyle.shrinkToFit(smallest = 10.5.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false)
                ) {
                    menuExpanded = !menuExpanded
                }
                .padding(horizontal = 4.dp, vertical = 8.dp)
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 172.dp)
        ) {
            enumValues<T>().forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(sortTypeText(type)),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = if (sortType == type) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onSortTypeChange(type)
                        menuExpanded = false
                    }
                )
            }
        }

        if (sortType != PlaylistSongSortType.CUSTOM) {
            val sortOrderName = stringResource(if (sortDescending) R.string.sort_order_descending else R.string.sort_order_ascending)
            ResizableIconButton(
                icon = if (sortDescending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .padding(8.dp)
                    // The arrow says which way the list runs, and had no words for it.
                    .semantics { contentDescription = sortOrderName },
                onClick = { onSortDescendingChange(!sortDescending) }
            )
        }
    }
}
