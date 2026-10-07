/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.R

/**
 * The welcome back page: what is new since somebody was last here, and a way to each thing.
 *
 * The tour points at controls, which is the right way to teach where a button is and no way at all
 * to say that there is a widget now, or a history, or a setting three screens down. After an update
 * a returning user used to get the tour's one or two new bubbles and nothing else. This is the page
 * those belong on: every new thing in a line, each with one button that leads to it.
 *
 * "Show me" does not describe the thing again. It closes this page and takes the tour to where the
 * thing lives, by the way somebody would go there themselves, and then comes back here. So the page
 * is the place to come back to, and what has been looked at is ticked off.
 *
 * The walk round Settings sits at the end whatever is new, because Settings is where people get
 * lost, and more so after an update has added to it.
 *
 * A dialog over the app and not a destination, like the questions screen it comes after: the tour
 * it starts needs the real screens underneath, and a destination would be one of them.
 *
 * Its window is laid out over the whole screen (decorFitsSystemWindows = false). Left to fit
 * inside the system bars it stopped at the top of the navigation bar, and the app's own bar, which
 * is drawn under that, showed through below the page. So the page now reaches the bottom edge and
 * keeps its content clear of the bars itself. The top is left as it was, starting under the status
 * bar, so that nothing changes behind the status bar's icons.
 *
 * For a screen reader the window is named, each button says which thing it is for, since seven
 * buttons all reading "Show me" tell nobody apart, and the titles that head a group are headings.
 *
 * @param things what to list, newest first (newThingsFor)
 * @param seen the ids already looked at this time round
 * @param returning false when it was opened from Settings by somebody who has not just updated,
 *   and the words then do not pretend otherwise
 */
@Composable
fun WelcomeBack(
    things: List<NewThing>,
    seen: Set<String>,
    returning: Boolean,
    onShow: (NewThing) -> Unit,
    onShowSettings: () -> Unit,
    onDone: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
            windowTitle = stringResource(R.string.welcome_back_title),
        )
    ) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Inside the scroll, so the page's colour runs to the edge and under the
                // navigation bar while the last button can still be brought clear of it.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(vertical = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WavingHand,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(80.dp)
                    .padding(16.dp),
            )
            Text(
                text = stringResource(R.string.welcome_back_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .semantics { heading() }
            )
            Text(
                text = stringResource(if (returning) R.string.welcome_back_body else R.string.welcome_back_everything),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            // One block for each release, newest first, which is the order the list is in.
            var release: String? = null
            for (thing in things) {
                if (thing.release != release) {
                    release = thing.release
                    Text(
                        text = stringResource(R.string.welcome_back_release, thing.release),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 4.dp)
                            .semantics { heading() }
                    )
                }
                ThingCard(
                    icon = thing.icon,
                    title = stringResource(thing.title),
                    body = stringResource(thing.body),
                    action = stringResource(
                        if (thing.action == NewThingAction.ADD_WIDGET) R.string.welcome_back_add_widget else R.string.welcome_back_show
                    ),
                    done = thing.id in seen,
                    onClick = { onShow(thing) },
                )
            }

            Spacer(Modifier.size(12.dp))
            ThingCard(
                icon = Icons.Rounded.Explore,
                title = stringResource(R.string.welcome_back_settings_title),
                body = stringResource(R.string.welcome_back_settings_body),
                action = stringResource(R.string.welcome_back_settings_show),
                done = SETTINGS_WALK in seen,
                // Not under any release's heading: it is a group of its own, of one.
                heading = true,
                onClick = onShowSettings,
            )

            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(stringResource(R.string.welcome_back_done))
            }
        }
    }
    }
}

/** The id the walk round Settings is ticked off under, beside the ids of the new things. */
const val SETTINGS_WALK = "settings_walk"

/**
 * One thing: what it is, a sentence, and the one button that leads to it. Ticked once it has been
 * looked at, and the button stays, since looking twice is allowed.
 *
 * The button shows only [action], and is read out with the [title] after it. The tick is read as
 * the button's state and not as a picture inside it.
 */
@Composable
private fun ThingCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    done: Boolean,
    heading: Boolean = false,
    onClick: () -> Unit,
) {
    val actionForThis = stringResource(R.string.welcome_back_action_for, action, title)
    val seenState = stringResource(R.string.welcome_back_seen)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = if (heading) Modifier.semantics { heading() } else Modifier,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .semantics {
                        contentDescription = actionForThis
                        if (done) stateDescription = seenState
                    }
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(action)
            }
        }
    }
}
