/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun ColumnScope.SettingsClickToReveal(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // rememberSaveable, so an expanded section survives a rotation. On a tablet that is a common
    // way to lose your place.
    var showContent by rememberSaveable {
        mutableStateOf(false)
    }

    // Rotate one icon rather than swapping two. ExpandLess is the exact point reflection of
    // ExpandMore, so this looks identical at rest but sweeps instead of flipping in one frame.
    // Same spring as the AnimatedVisibility below, or the arrow settles before the panel does and
    // the two still read as separate events.
    val chevronRotation by animateFloatAsState(
        targetValue = if (showContent) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron"
    )

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.elevatedShape)
            .clickable(
                onClickLabel = title,
                role = Role.Button,
                onClick = {
                    showContent = !showContent
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            )
            .semantics {
                stateDescription = if (showContent) "Expanded" else "Collapsed"
            }
    ) {
        PreferenceGroupTitle(
            title = title,
            modifier = Modifier
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .graphicsLayer { rotationZ = chevronRotation }
        )
    }
    AnimatedVisibility(showContent) {
        Column {
            content()
        }
    }
}