/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TogetherTourSeenKey
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.RoomTourPicture
import com.dd3boh.outertune.ui.component.RoomTourScene
import com.dd3boh.outertune.ui.utils.OpensOnScreen
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.editOrSay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How rooms work: a few pages, each a moving picture of the room and a sentence or two (Mel, #testers 22 Nov 2026:
 * "there should be an animated tutorial for all the features / quirks of listen together", and Ti-Die's "a LITTLE
 * tutorial"). Shown by itself once, the first time this phone is in a room (RoomTourOnFirstRoom), and any time from
 * the room's sheet and Settings > Listen together (HowRoomsWork). Skip closes it at once, from any page.
 *
 * Every word is in strings, so the pages can follow the room as it changes.
 */
internal enum class RoomTourPage(val scene: RoomTourScene, @StringRes val title: Int, @StringRes val body: Int) {
    IN_STEP(RoomTourScene.IN_STEP, R.string.together_tour_step_title, R.string.together_tour_step_body),
    ADD(RoomTourScene.ADD, R.string.together_tour_add_title, R.string.together_tour_add_body),
    CONTROL(RoomTourScene.CONTROL, R.string.together_tour_control_title, R.string.together_tour_control_body),
    OWN(RoomTourScene.OWN, R.string.together_tour_own_title, R.string.together_tour_own_body),
}

/** Whether the tour opens by itself: this phone is in a room, and it never has before. */
internal fun roomTourDue(inRoom: Boolean, seen: Boolean?): Boolean = inRoom && seen != true

/** A window lower than this is a phone on its side: the picture goes beside the words. */
private const val BESIDE_BELOW_DP = 480

@Composable
fun RoomTourDialog(onDismiss: () -> Unit) {
    val pages = RoomTourPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val beside = LocalConfiguration.current.screenHeightDp < BESIDE_BELOW_DP
    val last = pager.currentPage == pages.lastIndex
    // Not closed by a tap beside it: it opens by itself as the room starts, often under a finger on its way to the sheet.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = if (beside) 640.dp else 420.dp)
                .fillMaxWidth(),
        ) {
            // It scrolls where it cannot fit, a small phone on its side with large text: nothing of it is out of reach.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Skip, from the first page on. Kept in its place on the last page, where the tick is Done.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !last,
                        modifier = if (last) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier,
                    ) {
                        Text(stringResource(R.string.together_tour_skip))
                    }
                }
                HorizontalPager(state = pager, verticalAlignment = Alignment.Top) { index ->
                    TourPage(pages[index], running = pager.settledPage == index, beside = beside)
                }
                // Back, how far along, and on: the row under setup's pages, a tick on the last.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                        },
                        enabled = pager.currentPage > 0,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    // Follows the finger as the pages are swiped.
                    val along = (pager.currentPage + pager.currentPageOffsetFraction + 1f) / pages.size
                    val shown by animateFloatAsState(along, tween(300, easing = FastOutSlowInEasing), label = "tourProgress")
                    LinearProgressIndicator(
                        progress = { shown },
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                            .height(4.dp),
                    )
                    FilledIconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        // Done goes on to the walk over the room's sheet, when one is on screen (RoomWalk); Skip skips both.
                        if (last) {
                            // Read to its end, from the room's sheet or Settings too: the first room does not open it
                            // again (RoomTourOnFirstRoom). NonCancellable: closing cancels this scope (rememberPreference).
                            scope.launch(NonCancellable) { context.editOrSay { it[TogetherTourSeenKey] = true } }
                            if (RoomWalkAsk.areas > 0) RoomWalkAsk.wanted = true
                            onDismiss()
                        } else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }) {
                        Icon(
                            if (last) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = stringResource(if (last) R.string.action_done else R.string.action_next),
                        )
                    }
                }
            }
        }
    }
}

/** One page: its picture, its title and its words; beside each other on a phone on its side. */
@Composable
private fun TourPage(page: RoomTourPage, running: Boolean, beside: Boolean) {
    if (beside) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            RoomTourPicture(page.scene, running, Modifier.weight(1f), height = 150.dp)
            Spacer(Modifier.width(20.dp))
            TourWords(page, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            RoomTourPicture(page.scene, running)
            Spacer(Modifier.height(20.dp))
            TourWords(page, Modifier.fillMaxWidth())
        }
    }
}

/**
 * [page]'s title and words, over the others' drawn unseen: every page is as tall as the tallest, so the pages and the
 * row under them stay where they are as they are swiped.
 */
@Composable
private fun TourWords(page: RoomTourPage, modifier: Modifier = Modifier) {
    Box(modifier) {
        RoomTourPage.entries.forEach { other ->
            val mine = other == page
            Column(
                if (mine) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics { }
            ) {
                Text(stringResource(other.title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(other.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "How rooms work", in the room's sheet and in Settings > Listen together: opens the tour. */
@Composable
fun HowRoomsWork(modifier: Modifier = Modifier) {
    var touring by rememberSaveable { mutableStateOf(false) }
    if (touring) RoomTourDialog(onDismiss = { touring = false })
    TextButton(onClick = { touring = true }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.together_tour_entry))
    }
}

/**
 * The tour, by itself, the first time this phone is in a room: started here, joined from the sheet, a link or a QR
 * code. Shown wherever the app is, as a proposal is (TogetherProposalDialog), and marked as seen as it opens, so that
 * it never comes back by itself. A room this phone is in already when the tour first comes with an update counts too:
 * the testers who were in rooms before it see it once.
 */
@Composable
fun RoomTourOnFirstRoom(connection: PlayerConnection?) {
    val context = LocalContext.current
    val inRoom = connection?.service?.together?.collectAsState()?.value != null
    var showing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(inRoom) {
        if (!roomTourDue(inRoom, context.dataStore.data.first()[TogetherTourSeenKey])) return@LaunchedEffect
        context.dataStore.edit { it[TogetherTourSeenKey] = true }
        showing = true
    }
    // It comes up by itself as the room starts, not for a tap: its window waits for the app to be on screen.
    if (showing) OpensOnScreen { RoomTourDialog(onDismiss = { showing = false }) }
}
