/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.ui.utils.GlassSpec
import com.dd3boh.outertune.ui.utils.isScrollingUp
import com.dd3boh.outertune.ui.utils.rememberGlassSpec

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && lazyListState.isScrollingUp(), onClick) { Icon(painter = painterResource(icon), contentDescription = null) }

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyGridState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && lazyListState.isScrollingUp(), onClick) { Icon(painter = painterResource(icon), contentDescription = null) }

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyGridState,
    icon: ImageVector,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && lazyListState.isScrollingUp(), onClick) { Icon(imageVector = icon, contentDescription = null) }

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    scrollState: ScrollState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && scrollState.isScrollingUp(), onClick) { Icon(painter = painterResource(icon), contentDescription = null) }

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    icon: ImageVector,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && lazyListState.isScrollingUp(), onClick) { Icon(imageVector = icon, contentDescription = null) }

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    scrollState: ScrollState,
    icon: ImageVector,
    onClick: () -> Unit,
) = HideOnScrollFAB(visible && scrollState.isScrollingUp(), onClick) { Icon(imageVector = icon, contentDescription = null) }

/**
 * The one implementation behind the overloads above, which only differ in what they read "scrolling
 * up" from and how the icon is given. Slides in at the bottom end, above the player and the tabs.
 */
@Composable
private fun BoxScope.HideOnScrollFAB(visible: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
    ) {
        FloatingButton(onClick = onClick, content = icon)
    }
}

/**
 * The button itself. Glass while its screen can draw it (see TopBarGlass.kt), in the button's own
 * container colour so it still reads as a button, and a plain FAB otherwise.
 *
 * Drawn by the screen's host like the top bar, and for the same reason: the button is inside the
 * screen, so it cannot read a backdrop of that screen from where it is composed.
 */
@Composable
private fun FloatingButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalTopBarGlassHost.current
    val glass = if (host != null) rememberGlassSpec() else null
    if (host == null || glass == null) {
        FloatingActionButton(modifier = Modifier.padding(16.dp), onClick = onClick, content = content)
        return
    }
    val spec = GlassSpec(host.backdrop, glass.intensity)
    val shape = FloatingActionButtonDefaults.shape
    FloatingActionButton(
        modifier = Modifier
            .padding(16.dp)
            .drawnBy(host)
            .graphicsLayer()
            .floatingGlass(spec, shape, MaterialTheme.colorScheme.primaryContainer.copy(alpha = spec.tintAlpha(min = 0.55f, max = 0.95f))),
        onClick = onClick,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        // The glass carries its own rim and shadow; the stock elevation would draw a second,
        // square-ish shadow under a transparent container.
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        content = content,
    )
}
