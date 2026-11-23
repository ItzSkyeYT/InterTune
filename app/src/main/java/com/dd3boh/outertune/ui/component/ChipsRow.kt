/*
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
fun <E> ChipsRow(
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: (E) -> Boolean = { false }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.width(12.dp))

        chips.forEach { (value, label) ->
            FilterChip(
                label = { Text(label) },
                selected = currentValue == value,
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = { onValueUpdate(value) },
                trailingIcon = {
                    if (isLoading(value)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )

            Spacer(Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <E> ChipsLazyRow(
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    modifier: Modifier = Modifier,
    selected: ((E) -> Boolean)? = null,
    isLoading: (E) -> Boolean = { false }
) {
    val haptic = LocalHapticFeedback.current
    val tween: FiniteAnimationSpec<Float> = tween(
        durationMillis = 300,
        easing = FastOutSlowInEasing
    )

    val placementTween: FiniteAnimationSpec<IntOffset> = tween(
        durationMillis = 300,
        easing = LinearOutSlowInEasing
    )

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
    ) {
        item(
            key = "spacer"
        ) {
            Spacer(Modifier.width(12.dp))
        }

        items(
            items = chips,
            key = { it.second }
        ) {(value, label) ->
            FilterChip(
                label = { Text(label) },
                selected = selected?.let { it(value) } ?: (currentValue == value),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = {
                    onValueUpdate(value)
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec =  tween,
                        placementSpec = placementTween,
                        fadeOutSpec = tween
                    ),
                trailingIcon = {
                    if (isLoading(value)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )

            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * A chip that opens something instead of picking something: its words, a picture before them, and
 * what a tap does (Folders' Local scanner, which opens Local media).
 */
@Immutable
data class ActionChip(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * A row of [ActionChip]s, in the shape, height, spacing, colours and border of the chips of the
 * other [ChipsRow] above them, so the Folders tab's row sits among the Library's chips as one of
 * them. They are never picked, so they have no current value, and a screen reader says button
 * where it would say checkbox for a chip that picks.
 */
@Composable
fun ChipsRow(
    chips: List<ActionChip>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.width(12.dp))

        chips.forEach { chip ->
            AssistChip(
                onClick = chip.onClick,
                label = { Text(chip.label) },
                leadingIcon = {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false),
            )

            Spacer(Modifier.width(8.dp))
        }
    }
}
