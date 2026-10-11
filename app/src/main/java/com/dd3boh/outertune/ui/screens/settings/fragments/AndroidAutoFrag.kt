/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ExplainDialog
import com.dd3boh.outertune.ui.component.PreferenceEntry

/**
 * Why the app is missing from a car, and the way to bring it in. Android Auto lists only what
 * Google Play installed, and no copy of this app comes from there, so everybody who wants it
 * in the car has to find a switch Android Auto keeps out of sight. Nothing here is a setting:
 * the row opens the three steps.
 */
@Composable
fun ColumnScope.AndroidAutoFrag() {
    var open by rememberSaveable { mutableStateOf(false) }

    PreferenceEntry(
        title = { Text(stringResource(R.string.android_auto)) },
        description = stringResource(R.string.android_auto_missing),
        icon = { Icon(Icons.Rounded.DirectionsCar, null) },
        onClick = { open = true },
    )

    if (open) {
        ExplainDialog(
            title = stringResource(R.string.android_auto),
            body = stringResource(R.string.android_auto_missing_body),
            onDismiss = { open = false },
        )
    }
}
