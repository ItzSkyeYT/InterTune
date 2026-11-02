/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.NoAccounts
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.utils.WhyStopped
import com.dd3boh.outertune.utils.WhyStopped.Cause
import com.dd3boh.outertune.utils.YouTubeRegion
import com.dd3boh.outertune.utils.YouTubeTrouble
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Why search, a radio, a mix or Home stopped loading, with the button that fixes it, where the
 * listener meets it (WhyStopped): a sign-in YouTube no longer accepts, with Sign in again, or a
 * connection that does not reach YouTube, with Retry and the proxy switch.
 *
 * The look of [RegionNote] ([NoteCard]), since it is the same kind of thing: a word from the app
 * about why something is missing. [onRetry] null leaves Retry out, for Home, which loads again by
 * itself once the sign-in changes and has its pull.
 */
@Composable
fun WhyStoppedNote(
    cause: Cause,
    onSignIn: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onProxy: () -> Unit = ::openProxySetting,
    margin: Dp = NoteMargin,
) {
    when (cause) {
        Cause.SIGN_IN -> NoteCard(
            icon = Icons.Rounded.NoAccounts,
            title = stringResource(R.string.why_stopped_sign_in_title),
            body = stringResource(R.string.why_stopped_sign_in_body),
            modifier = modifier,
            margin = margin,
        ) {
            if (onRetry != null) NoteButton(stringResource(R.string.retry), onRetry)
            NoteButton(stringResource(R.string.why_stopped_sign_in_again), onSignIn)
        }

        Cause.UNREACHABLE -> NoteCard(
            icon = Icons.Rounded.CloudOff,
            title = stringResource(R.string.why_stopped_unreachable_title),
            body = stringResource(R.string.why_stopped_unreachable_body),
            modifier = modifier,
            margin = margin,
        ) {
            NoteButton(stringResource(R.string.region_note_proxy), onProxy)
            if (onRetry != null) NoteButton(stringResource(R.string.retry), onRetry)
        }

        Cause.REGION -> RegionNote(onProxy = onProxy, onDone = null, modifier = modifier, margin = margin)
    }
}

/** Signed in as the Account page has it, followed as the sign-in changes. */
@Composable
fun rememberSignedIn(): Boolean {
    val cookie by rememberPreference(InnerTubeCookieKey, defaultValue = "")
    return WhyStopped.signedIn(cookie)
}

/**
 * Asks a page's request again when the sign-in changes while the page shows a failure: back from
 * Sign in again, the page fills by itself, as Home does.
 */
@Composable
fun RetryWhenSignInChanges(failed: Boolean, retry: () -> Unit) {
    val cookie by rememberPreference(InnerTubeCookieKey, defaultValue = "")
    val onOpen = remember { cookie }
    val stillFailed by rememberUpdatedState(failed)
    val ask by rememberUpdatedState(retry)
    LaunchedEffect(cookie) {
        if (cookie != onOpen && stillFailed) ask()
    }
}

/**
 * In place of [LoadFailed] on a page whose request failed with [kind]: the note for its cause,
 * or [LoadFailed] as it was when the cause is not clear. For a closed country the region's note
 * stands over [LoadFailed], as on the album page since #68; [region] false leaves that to a note
 * the page shows anyway (search, Home). [onRetry] null draws nothing when no cause is clear.
 */
@Composable
fun FailedNote(
    kind: WhyStopped.Kind?,
    onSignIn: () -> Unit,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    region: Boolean = true,
    onProxy: () -> Unit = ::openProxySetting,
    margin: Dp = NoteMargin,
) {
    val seen by YouTubeRegion.seen.collectAsState()
    val cause = WhyStopped.cause(
        kind,
        signedIn = rememberSignedIn(),
        online = LocalNetworkConnected.current,
        regionClosed = region && YouTubeRegion.closed(seen),
    )
    when (cause) {
        Cause.SIGN_IN, Cause.UNREACHABLE -> WhyStoppedNote(cause, onSignIn, onRetry, modifier, onProxy, margin)
        Cause.REGION -> Column(modifier) {
            RegionNote(onProxy = onProxy, onDone = null, margin = margin)
            if (onRetry != null) LoadFailed(onRetry = onRetry)
        }

        null -> if (onRetry != null) LoadFailed(onRetry = onRetry, modifier = modifier)
    }
}

/**
 * Over the songs an album page already had, when asking YouTube for the album failed with [kind]:
 * the note for the cause when it is clear, as [FailedNote] has it, and otherwise that the album did
 * not load, with Retry. The album itself stays as it is stored, whatever the failure
 * (AlbumViewModel).
 */
@Composable
fun KeptAlbumNote(
    kind: WhyStopped.Kind,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seen by YouTubeRegion.seen.collectAsState()
    val cause = WhyStopped.cause(
        kind,
        signedIn = rememberSignedIn(),
        online = LocalNetworkConnected.current,
        regionClosed = YouTubeRegion.closed(seen),
    )
    if (cause != null) {
        WhyStoppedNote(cause, onSignIn, onRetry, modifier)
    } else {
        NoteCard(
            icon = Icons.Rounded.Album,
            title = stringResource(R.string.album_not_loaded_title),
            body = stringResource(R.string.album_not_loaded_body),
            modifier = modifier,
        ) {
            NoteButton(stringResource(R.string.retry), onRetry)
        }
    }
}

/**
 * The note on Home for the last clear failure anywhere ([YouTubeTrouble]): the sign-in or the
 * connection. A closed country has its own note there already ([RegionNoteIfClosed]).
 */
@Composable
fun WhyStoppedNoteIfAny(
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind by YouTubeTrouble.kind.collectAsState()
    val cause = WhyStopped.cause(kind, signedIn = rememberSignedIn(), online = LocalNetworkConnected.current, regionClosed = false)
    if (cause == Cause.SIGN_IN || cause == Cause.UNREACHABLE) {
        // Home loads again by itself when the sign-in changes, so Retry is only for the connection.
        WhyStoppedNote(cause, onSignIn, onRetry = onRetry.takeIf { cause == Cause.UNREACHABLE }, modifier = modifier)
    }
}

/**
 * The card of [RegionNote] and [AutoBlockerNote]: the app's own colour, a round picture, a title
 * and a few words, and its buttons under the words so they wrap in a large font or a long
 * language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    margin: Dp = NoteMargin,
    buttons: @Composable () -> Unit,
) {
    val onCard = MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = margin, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = onCard,
                    // The first line is level with the middle of the picture beside it.
                    modifier = Modifier.padding(top = 10.dp, end = 6.dp),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onCard,
                    modifier = Modifier.padding(top = 4.dp, end = 6.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    buttons()
                }
            }
        }
    }
}

/** The space either side of a note: Home's and the pages'. The queue's rows stand 8 dp in, and its note with them. */
internal val NoteMargin = 12.dp

@Composable
internal fun NoteButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
    ) {
        Text(text)
    }
}
