/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.InsetsSafeS

/**
 * A guided run through what the app can do, and through what is new since last time.
 *
 * Not a carousel of screenshots. Every step that has somewhere to go carries a button that goes
 * there, because the thing people fail to do is not understand a feature, it is find it again
 * afterwards. Leaving by that button counts as having been walked through, so nobody is made to
 * finish the tour before they are allowed to use what it just showed them.
 *
 * Skip is on screen from the first frame and never moves. A tour somebody cannot get out of is
 * worse than no tour: it is the first thing the app does to them, and it teaches that the app
 * wastes their time.
 */
@Composable
fun WalkthroughScreen(
    steps: List<WalkthroughStep>,
    onNavigate: (String) -> Unit,
    onFinish: () -> Unit,
) {
    if (steps.isEmpty()) {
        // Nothing new. Finishing immediately rather than showing a page that congratulates
        // somebody for being up to date.
        onFinish()
        return
    }

    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(steps.indices)]
    val last = index == steps.lastIndex

    // Back steps backwards rather than leaving, until there is nowhere back to go. Leaving from
    // the first step is the same as skipping, which is allowed.
    BackHandler {
        if (index > 0) index-- else onFinish()
    }

    // A Dialog rather than something drawn in place. Composed inline it is a sibling of the main
    // content and the app simply draws over it: the first attempt put the progress and the skip
    // button above the search bar with Home showing through. Not dismissable by touching outside
    // either, because Skip is right there and an accidental tap should not count as having read it.
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(InsetsSafeS)
                .padding(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.walkthrough_progress, index + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.walkthrough_skip))
                }
            }

            Spacer(Modifier.height(8.dp))

            Dots(count = steps.size, current = index)

            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    // Direction follows travel, so going back does not feel like going on.
                    val forward = targetState > initialState
                    val width = if (forward) 1 else -1
                    (slideInHorizontally { it / 6 * width } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally { -it / 6 * width } + fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                },
                label = "step",
                modifier = Modifier.weight(1f),
            ) { shown ->
                val current = steps[shown.coerceIn(steps.indices)]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            imageVector = current.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp),
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    Text(
                        text = stringResource(current.title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = stringResource(current.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    current.route?.let { route ->
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(
                            onClick = {
                                // Going there ends the walkthrough. Somebody who followed a step
                                // has done the thing it was for, and dropping them back into the
                                // tour afterwards would be taking it away again.
                                onFinish()
                                onNavigate(route)
                            },
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Text(stringResource(current.action ?: R.string.walkthrough_action_open))
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Holds its place when there is nothing to go back to, so Next does not jump
                // sideways between the first step and the second.
                Box(modifier = Modifier.weight(1f)) {
                    if (index > 0) {
                        TextButton(onClick = { index-- }) {
                            Text(stringResource(R.string.walkthrough_back))
                        }
                    }
                }
                Button(
                    onClick = { if (last) onFinish() else index++ },
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        stringResource(
                            if (last) R.string.walkthrough_done else R.string.walkthrough_next
                        )
                    )
                }
            }
        }
    }
    }
}

/** Where you are, as a row of dots that widen rather than a bar that fills. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        for (i in 0 until count) {
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, label = "dot")
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}
