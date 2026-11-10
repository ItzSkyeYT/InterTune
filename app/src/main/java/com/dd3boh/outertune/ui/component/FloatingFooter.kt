package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.constants.MenuCornerRadius

/**
 * The pill that floats over the foot of a list while songs are picked: the pages' selection bar.
 * The queue sheet uses it too, over the bar at its own foot, so a selection looks the same in every
 * list. [insets] is the room kept clear at the edges, the pages' own (under the mini player and the
 * dock) unless the queue gives its own; [lift] is more room under the pill, for a bar it floats over.
 */
@Composable
fun FloatingFooter(
    visible: Boolean,
    modifier: Modifier = Modifier,
    insets: WindowInsets = LocalPlayerAwareWindowInsets.current,
    lift: Dp = 0.dp,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(insets.union(WindowInsets.ime))
            .padding(bottom = 16.dp + lift)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(modifier)
        ) {
            Surface(
                shape = RoundedCornerShape(MenuCornerRadius),
                tonalElevation = 8.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .wrapContentHeight()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    content = content
                )
            }
        }
    }
}
