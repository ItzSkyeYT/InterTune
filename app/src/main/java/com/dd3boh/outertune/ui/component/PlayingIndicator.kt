package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Whether the pages are out of sight under the open player. MainActivity says so for the pages;
 * the player's own sheet, which is over them, keeps the default and is never hidden.
 */
val LocalHiddenByPlayer = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
fun PlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    bars: Int = 3,
    barWidth: Dp = 4.dp,
    cornerRadius: Dp = ThumbnailCornerRadius,
    isPlaying: Boolean = true
) {
    val animatables = remember {
        List(bars) {
            Animatable(0.1f)
        }
    }

    // The bars hold still while the open player covers the page they are on. Every step of
    // theirs is a frame drawn for the whole window, and a page under the player kept taking them:
    // 88 frames a second and half a processor core on a Pixel 5, for bars nobody could see.
    val hidden = LocalHiddenByPlayer.current
    LaunchedEffect(isPlaying) {
        snapshotFlow { hidden() }.collectLatest { covered ->
            if (covered) return@collectLatest
            coroutineScope {
                animatables.forEach { animatable ->
                    launch {
                        while (true) {
                            if (isPlaying)
                                animatable.animateTo(Random.nextFloat() * 0.9f + 0.1f)
                            else
                                animatable.animateTo(0.15f)
                            delay(50)
                        }
                    }
                }
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(barWidth * 1.5f),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
    ) {
        animatables.forEach { animatable ->
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(barWidth)
            ) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x = 0f, y = size.height * (1 - animatable.value)),
                    size = size.copy(height = animatable.value * size.height),
                    cornerRadius = CornerRadius(cornerRadius.toPx())
                )
            }
        }
    }
}

@Composable
fun PlayingIndicatorBox(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    playWhenReady: Boolean,
    color: Color = Color.White,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
        ) {
            if (playWhenReady) {
                PlayingIndicator(
                    color = color,
                    modifier = Modifier.height(24.dp)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = color
                )
            }
        }
    }
}
