/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.AppearanceMiscFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SwipeGesturesFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabArrangementFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabExtrasFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ThemeAppFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ThemePlayerFrag
import com.dd3boh.outertune.ui.utils.backToMain

/**
 * Everything about how the app looks and how you move around it.
 *
 * This was two screens, Appearance and Interface, and the last row of each was a link to the
 * other. That pair of links was the code admitting the line between them was arbitrary: the
 * navigation bar's slim mode sat under Appearance while the arrangement of the tabs it shows sat
 * under Interface. Together they are sixteen rows, which is an ordinary length for one screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LookAndFeelSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.theme)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemeAppFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemePlayerFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_layout)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            TabArrangementFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            TabExtrasFrag()
            AppearanceMiscFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_behavior)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SwipeGesturesFrag()
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.look_and_feel)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
