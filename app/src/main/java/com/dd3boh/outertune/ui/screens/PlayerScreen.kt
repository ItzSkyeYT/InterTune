package com.dd3boh.outertune.ui.screens

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.extensions.supportsWideScreen
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.dismissedAnchor
import com.dd3boh.outertune.ui.component.expandedAnchor
import com.dd3boh.outertune.ui.component.rememberBottomSheetState

import com.dd3boh.outertune.ui.player.BottomSheetPlayer
import com.dd3boh.outertune.ui.player.LandscapePlayer
import com.dd3boh.outertune.ui.player.PortraitPlayer
import com.dd3boh.outertune.ui.player.QueueScreen
import com.dd3boh.outertune.ui.player.QueueSheet
import com.dd3boh.outertune.utils.rememberEnumPreference

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val TAG = "PlayerScreen"

    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current


    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
//            .background(MaterialTheme.colorScheme.surface)
    ) {
        Log.v(TAG, "PLR-3.0")

        val state = rememberBottomSheetState(
            dismissedBound = 0.dp,
            expandedBound = maxHeight,
            collapsedBound = MiniPlayerHeight,
            initialAnchor = expandedAnchor,
        )

        val tabMode = context.tabMode()
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && !tabMode && context.supportsWideScreen()) {
            LandscapePlayer(state, navController)
        } else {
            PortraitPlayer(state, navController)
        }
    }
}
