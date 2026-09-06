/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.constants.TabletUiKey
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)
    val (tabletUi, onTabletUiChange) = rememberPreference(TabletUiKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )

    // Moved off the Experimental screen. Forcing the wide layout is a preference about how the
    // app looks on this device, not an experiment, and nobody hunting for it would think to look
    // behind a warning triangle.
    SwitchPreference(
        title = { Text(stringResource(R.string.tablet_ui_title)) },
        description = stringResource(R.string.tablet_ui_title_description),
        icon = { Icon(Icons.Rounded.Devices, null) },
        checked = tabletUi,
        onCheckedChange = onTabletUiChange
    )
}