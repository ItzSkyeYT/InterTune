/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TogetherNameKey
import com.dd3boh.outertune.constants.TogetherRelaysKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.together.RoomCode
import com.dd3boh.outertune.together.TogetherSession
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.ui.utils.shrinkToFit
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import android.view.WindowManager
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.dd3boh.outertune.together.RoomQr
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Whether Listen together is offered: while it is built (Unreleased.LISTEN_TOGETHER), and from
 * Android 8, the first to have the key derivation the room code's seal needs (PBKDF2WithHmacSHA256).
 */
val togetherAvailable: Boolean
    get() = Unreleased.LISTEN_TOGETHER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/**
 * Listen together, from the player's menu. Out of a room: two phones on one song, a big Start, and
 * the field for a friend's code. In one: its code to pass on, the song the room plays, for a guest
 * how close it follows, and who is in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service
    val session by service.together.collectAsState()
    val song by playerConnection.mediaMetadata.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val landscape = LocalLandscape.current

    fun open(code: RoomCode, owner: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { service.startTogether(code, owner, togetherRelays(context), togetherName(context)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        AnimatedContent(
            targetState = session,
            contentKey = { it?.code?.raw },
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(240)) { it / 16 }) togetherWith fadeOut(tween(120))
            },
            label = "together",
        ) { room ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp)
            ) {
                if (room == null) {
                    StartPage(
                        cover = song?.thumbnailUrl,
                        onStart = { open(RoomCode.generate(), owner = true) },
                        onJoin = { open(it, owner = false) },
                    )
                } else {
                    RoomPage(room, song, onLeave = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        room.leave()
                    })
                }
            }
        }
    }
}

@Composable
private fun StartPage(cover: String?, onStart: () -> Unit, onJoin: (RoomCode) -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    var typed by rememberSaveable { mutableStateOf("") }
    val parsed = RoomCode.parse(typed)
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        RoomScanDialog(
            onFound = {
                scanning = false
                onJoin(it)
            },
            onDismiss = { scanning = false },
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        InStepPicture(cover, Modifier.padding(top = 4.dp, bottom = 20.dp))
        Text(stringResource(R.string.together_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.together_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.together_start))
        }
        OrLine(stringResource(R.string.together_or_join), Modifier.padding(vertical = 20.dp))
        // Bottom-aligned: the field's label sits above its box, and the box is what the button lines up with.
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = codeTyped(it, before = typed) },
                label = { Text(stringResource(R.string.together_code)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
                visualTransformation = CodeDashes,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { parsed?.let(onJoin) }),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.nativeClipboard.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.let { typed = codeTyped(it.toString(), before = typed) }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.together_paste))
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onJoin) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp),
            ) { Text(stringResource(R.string.together_join)) }
        }
        // The QR code on the phone beside this one, read by this one's camera: joined at once.
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { scanning = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.together_scan))
        }
    }
}

@Composable
private fun RoomPage(room: TogetherSession, song: MediaMetadata?, onLeave: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val status by room.status.collectAsState()
    val copied = stringResource(R.string.together_copied)
    val invite = stringResource(R.string.together_invite_text, room.code.pretty, room.code.appLink)
    var showingQr by rememberSaveable { mutableStateOf(false) }
    if (showingQr) RoomQrDialog(room.code, onDismiss = { showingQr = false })
    // The owner's leaving ends the room for everyone: asked first (his word of 17 Oct 2026).
    var confirmingClose by rememberSaveable { mutableStateOf(false) }
    if (confirmingClose) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
            title = { Text(stringResource(R.string.together_close_room_title)) },
            text = { Text(stringResource(R.string.together_close_room_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClose = false
                    onLeave()
                }) { Text(stringResource(R.string.together_close_room), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClose = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    fun copy() {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        clipboard.nativeClipboard.setPrimaryClip(ClipData.newPlainText(room.code.pretty, room.code.pretty))
        // From Android 13 the system shows what was copied by itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.together_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(connected = status.relaysConnected > 0)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(
                        when {
                            room.owner -> R.string.together_live_hosting
                            status.leading -> R.string.together_you_control
                            else -> R.string.together_live_guest
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (status.relaysConnected == 0) stringResource(R.string.together_connecting)
                    else stringResource(R.string.together_relays_of, status.relaysConnected, room.relayCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Someone else leads: who, and for the owner the way back.
        val leaderName = status.leaderName
        if (!status.leading && leaderName != null) {
            Spacer(Modifier.height(14.dp))
            ControlRow(leaderName, canTakeBack = room.owner, onTakeBack = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                room.takeBack()
            })
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.together_code))
        Spacer(Modifier.height(10.dp))
        CodeChips(room.code, onClick = ::copy)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            // For a phone beside this one: its camera reads the code. Before Copy and Share,
            // which keep their places and their words.
            // Copy's own outline: the icon button's default is the stronger one, and it read heavier than its neighbours.
            OutlinedIconButton(onClick = { showingQr = true }, border = ButtonDefaults.outlinedButtonBorder(enabled = true)) {
                Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.together_qr))
            }
            OutlinedButton(onClick = ::copy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.together_copy), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite), null)
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.together_share), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Hairline()
        SectionLabel(stringResource(R.string.together_playing))
        Spacer(Modifier.height(12.dp))
        val hostSong = status.hostSong
        when {
            // The cover and the names from this phone's own player when it plays what the room plays.
            song != null && (status.leading || song.id == status.hostSongId) ->
                SongRow(song.thumbnailUrl, song.title, song.artists.joinToString { it.name })
            !status.leading && hostSong != null -> SongRow(null, hostSong, null)
            else -> Text(
                stringResource(R.string.together_nothing_playing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!status.leading) {
            Spacer(Modifier.height(16.dp))
            when {
                status.waitingForHost -> Waiting()
                status.ownWay -> OwnWay(
                    proposed = status.proposed,
                    onPropose = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        room.propose()
                    },
                    onCatchUp = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        room.catchUp()
                    },
                )
                status.pausedHere -> PausedHere()
                else -> SyncMeter(status.driftMs, status.locked, status.toleranceMs)
            }
        }
        if (status.leading) status.lastSuggestion?.let {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.together_last_suggestion, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Hairline()
        SectionLabel(stringResource(R.string.together_people))
        Spacer(Modifier.height(12.dp))
        People(
            members = status.members,
            meOwner = room.owner,
            meLeading = status.leading,
            onGiveControl = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); room.handOver(it) },
            onKick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); room.kick(it) },
        )
        if (!status.leading) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.together_suggest_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { if (room.owner) confirmingClose = true else onLeave() },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            // The owner's leaving closes the room for everyone, and says so.
            Text(stringResource(if (room.owner) R.string.together_close_room else R.string.together_leave))
        }
    }
}

/** Two phones on the same song at the same moment: the cover on both, one playhead, waves from the host to the other. */
@Composable
private fun InStepPicture(cover: String?, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "in step")
    val head by motion.animateFloat(0.08f, 0.92f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "head")
    val wave by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2100, easing = LinearEasing)), label = "wave")
    val waveColor = MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PhoneSketch(cover, head)
        Canvas(Modifier.size(width = 56.dp, height = 72.dp)) {
            for (i in 0 until 3) {
                val p = (wave + i / 3f) % 1f
                val r = size.width * (0.2f + 0.8f * p)
                drawArc(
                    color = waveColor.copy(alpha = (1f - p) * 0.9f),
                    startAngle = -40f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(-r, size.height / 2 - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        PhoneSketch(cover, head)
    }
}

@Composable
private fun PhoneSketch(cover: String?, head: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val lines = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(width = 68.dp, height = 116.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(top = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(lines.copy(alpha = 0.45f)))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(lines.copy(alpha = 0.25f)))
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.width(46.dp).height(8.dp)) {
            val y = size.height / 2
            val x = size.width * head
            drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(primary, Offset(0f, y), Offset(x, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(primary, radius = 3.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun OrLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hairline() {
    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** On while a relay answers, with a slow pulse; grey while none does. */
@Composable
private fun LiveDot(connected: Boolean) {
    val color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val pulse by rememberInfiniteTransition(label = "live")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "pulse")
    // As wide as the dot, so that it lines up with the text above and below; the pulse spills over.
    Canvas(Modifier.size(8.dp)) {
        val r = size.minDimension / 2
        if (connected) drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = r + pulse * 5.dp.toPx())
        drawCircle(color, radius = r)
    }
}

/** The code in its three groups, read as one by TalkBack; a tap copies it where [onClick] is given. */
/**
 * The room's QR code, for a phone beside this one (his yes of 17 Oct 2026): its camera reads it
 * and opens the invite, the same question a shared link asks. The screen goes as bright as it can
 * while it is up, so that a camera reads it in a dim room, and it scrolls, so that nothing of it
 * is out of reach on a phone on its side.
 */
@Composable
private fun RoomQrDialog(code: RoomCode, onDismiss: () -> Unit) {
    val modules = remember(code) { RoomQr.modules(code.appLink) }
    val screen = LocalConfiguration.current
    // As large as fits: most of the width upright, about half the height on its side.
    val side = minOf(300, (screen.screenWidthDp * 0.62f).toInt(), (screen.screenHeightDp * 0.5f).toInt()).dp
    Dialog(onDismissRequest = onDismiss) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            // The dialog's own window: its brightness goes with it when it closes.
            window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
            onDispose { }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(stringResource(R.string.together_qr_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                QrPicture(modules, Modifier.size(side))
                Spacer(Modifier.height(14.dp))
                Text(
                    code.pretty,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.together_qr_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}

/**
 * [modules] black on white whatever the theme, since cameras read dark on light: a quiet zone of
 * four modules round them, and each module a whole number of pixels, so that no seam shows
 * between two.
 */
@Composable
private fun QrPicture(modules: Array<BooleanArray>, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        val n = modules.size
        val cell = floor(size.minDimension / (n + 8)).coerceAtLeast(1f)
        val left = ((size.width - cell * n) / 2f).roundToInt().toFloat()
        val top = ((size.height - cell * n) / 2f).roundToInt().toFloat()
        for (y in 0 until n) for (x in 0 until n) {
            if (modules[y][x]) drawRect(Color.Black, topLeft = Offset(left + x * cell, top + y * cell), size = Size(cell, cell))
        }
    }
}

@Composable
private fun CodeChips(code: RoomCode, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val copyLabel = stringResource(R.string.together_copy)
    val style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = code.pretty }
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = copyLabel, onClick = onClick) else Modifier),
    ) {
        code.raw.chunked(4).forEach { group ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            ) {
                Text(group, style = style, maxLines = 1, autoSize = style.shrinkToFit(smallest = 14.sp))
            }
        }
    }
}

@Composable
private fun SongRow(cover: String?, title: String, artist: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!artist.isNullOrBlank()) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How far this phone is from the host: a dot on a line, behind to the left and ahead to the right,
 * on a square-root scale so that the few ms that matter show and 400 ms reaches the end. The band
 * in the middle is in step, and the dot takes the main colour inside it.
 */
@Composable
private fun SyncMeter(driftMs: Long?, locked: Boolean, toleranceMs: Long) {
    // Locked, it is in step by its own measure: it leaves gaps within the tolerance be.
    val inStep = driftMs != null && (locked || abs(driftMs) <= IN_STEP_MS)
    val dot by animateColorAsState(
        if (inStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        label = "dot",
    )
    val place by animateFloatAsState(meterPlace(driftMs ?: 0L), spring(stiffness = Spring.StiffnessLow), label = "place")
    val track = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val tick = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        val apart = abs(driftMs ?: 0L).toInt()
        Text(
            when {
                driftMs == null -> stringResource(R.string.together_following)
                inStep -> stringResource(R.string.together_in_step, apart)
                else -> stringResource(R.string.together_catching_up, apart)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
        ) {
            val y = size.height / 2
            val ends = 7.dp.toPx()
            val half = size.width / 2
            val reach = half - ends
            drawLine(track, Offset(ends, y), Offset(size.width - ends, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            // The band is the tolerance set in Settings: inside it, nothing is corrected once locked.
            val bandHalf = reach * meterPlace(toleranceMs)
            drawLine(band, Offset(half - bandHalf, y), Offset(half + bandHalf, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tick, Offset(half, y - 6.dp.toPx()), Offset(half, y + 6.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            if (driftMs != null) drawCircle(dot, radius = 6.dp.toPx(), center = Offset(half - place * reach, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.together_behind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.together_ahead),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Waiting() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.together_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A follower paused by its listener: the room goes on, and play brings this phone back to it. */
@Composable
private fun PausedHere() {
    Text(
        stringResource(R.string.together_paused_here),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OwnWay(proposed: Boolean, onPropose: () -> Unit, onCatchUp: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.together_own_way), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onCatchUp) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.together_catch_up))
            }
        }
        Spacer(Modifier.height(10.dp))
        // What this phone plays on its own, offered to the one who leads, queue and all (his ask of
        // 17 Oct 2026). Sent once; another song of its own can be proposed again.
        OutlinedButton(onClick = onPropose, enabled = !proposed, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(if (proposed) R.string.together_proposed else R.string.together_propose))
        }
    }
}

/**
 * A guest's song and queue proposed to this phone, the one that leads: asked wherever the app is,
 * since the room's sheet need not be open. A yes plays them from where the guest is.
 */
@Composable
fun TogetherProposalDialog(connection: PlayerConnection?) {
    val room = connection?.service?.together?.collectAsState()?.value ?: return
    val status by room.status.collectAsState()
    val proposal = status.proposal ?: return
    val first = proposal.songs.first()
    val more = proposal.songs.size - 1
    AlertDialog(
        onDismissRequest = room::dismissProposal,
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        title = { Text(stringResource(R.string.together_proposal_title, proposal.by)) },
        text = {
            Column {
                SongRow(first.thumbnail.ifBlank { null }, first.title.ifBlank { first.id }, first.artist.ifBlank { null })
                if (more > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pluralStringResource(R.plurals.together_proposal_more, more, more),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = room::acceptProposal) { Text(stringResource(R.string.together_proposal_play)) } },
        dismissButton = { TextButton(onClick = room::dismissProposal) { Text(stringResource(R.string.together_proposal_later)) } },
    )
}

/**
 * This phone first, with the name the others see under "You", then the others as they are heard.
 * The owner wears a star, the phone everyone follows a ring. The owner taps or long-presses
 * someone to give them control or to send them out.
 */
@Composable
private fun People(
    members: List<TogetherSession.Member>,
    meOwner: Boolean,
    meLeading: Boolean,
    onGiveControl: (TogetherSession.Member) -> Unit,
    onKick: (TogetherSession.Member) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val myName by produceState(phoneName(context)) { value = togetherName(context) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Person(stringResource(R.string.together_you), myName, initials(myName), owner = meOwner, leader = meLeading, you = true)
        members.forEach { member ->
            key(member.key) {
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                var menu by remember { mutableStateOf(false) }
                AnimatedVisibility(shown, enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) {
                    Box {
                        Person(
                            member.name.ifBlank { "?" }, null, initials(member.name), owner = member.owner, leader = member.leader,
                            onPress = if (meOwner) {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menu = true
                                }
                            } else null,
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!member.leader) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.together_give_control)) },
                                    leadingIcon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                                    onClick = { menu = false; onGiveControl(member) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.together_kick)) },
                                leadingIcon = { Icon(Icons.Rounded.PersonRemove, contentDescription = null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error,
                                ),
                                onClick = { menu = false; onKick(member) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Person(
    name: String,
    detail: String?,
    initials: String,
    owner: Boolean,
    leader: Boolean,
    you: Boolean = false,
    onPress: (() -> Unit)? = null,
) {
    val ring = BottomSheetDefaults.ContainerColor
    val pressable = if (onPress != null) {
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onPress, onLongClick = onPress)
    } else Modifier
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp).then(pressable)) {
        Box {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    // The ring: this is the phone everyone follows.
                    .then(if (leader) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier)
                    .clip(CircleShape)
                    .background(if (you) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(
                    initials,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (you) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (owner) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.together_host),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(20.dp)
                        .border(2.dp, ring, CircleShape)
                        .padding(2.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Two lines: a phone's name ("Galaxy S25 Ultra") was cut after its first word or two on one.
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Someone else has control: who, and for the owner, the way to take it back. */
@Composable
private fun ControlRow(name: String, canTakeBack: Boolean, onTakeBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp),
    ) {
        Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.together_has_control, name),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (canTakeBack) TextButton(onClick = onTakeBack) { Text(stringResource(R.string.together_take_back)) }
    }
}

/** The relays a room uses: the public ones, or in a debug build those of TogetherRelaysKey when set. */
suspend fun togetherRelays(context: Context): List<String> =
    context.dataStore.data.first()[TogetherRelaysKey]
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        ?: TogetherSession.PUBLIC_RELAYS

/** The name the others see: the one set in Settings > Listen together, else the phone's own. */
suspend fun togetherName(context: Context): String =
    context.dataStore.data.first()[TogetherNameKey]?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: phoneName(context)

/** The phone's own name from its settings ("Galaxy S25 Ultra"), else its model. */
fun phoneName(context: Context): String {
    val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    } else null
    return named?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: Build.MODEL
}

/** Up to two letters for someone's circle: the first of each of the first two words of their phone's name. */
internal fun initials(name: String): String =
    name.split(' ', '_', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Where a gap sits on the meter, from -1 to 1 (positive: behind): the square root of its share of 400 ms. */
internal fun meterPlace(driftMs: Long): Float {
    val share = abs(driftMs).coerceAtMost(METER_MS) / METER_MS.toFloat()
    return sign(driftMs.toFloat()) * sqrt(share)
}

private const val METER_MS = 400L
private const val IN_STEP_MS = 25L
private const val LINK = "intertune://together/"

/**
 * What the code field keeps of what was typed or pasted, [before] being what it held. A code or a
 * link, alone or inside a whole invite (messengers do not open intertune:// links, so people paste
 * the message), gives its 12 letters. Typing keeps the letters a code can have, twelve at most.
 * Other long text, or a link with no code, holds none: the field stays as it was.
 */
internal fun codeTyped(input: String, before: String = ""): String {
    RoomCode.parse(input)?.let { return it.raw }
    val inside = Regex("$LINK([A-Za-z0-9]{${RoomCode.LENGTH}})", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1)
        ?: Regex("\\b[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}\\b").find(input)?.value
    RoomCode.parse(inside)?.let { return it.raw }
    if ("://" in input || input.count { it.isLetterOrDigit() } > RoomCode.LENGTH + 2) return before
    return input.uppercase().filter { it in RoomCode.ALPHABET }.take(RoomCode.LENGTH)
}

/** Shows the code as XXXX-XXXX-XXXX while the field holds its letters alone. */
internal object CodeDashes : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        return TransformedText(
            AnnotatedString(raw.chunked(4).joinToString("-")),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = offset + when {
                    offset <= 4 -> 0
                    offset <= 8 -> 1
                    else -> 2
                }

                override fun transformedToOriginal(offset: Int) = (offset - when {
                    offset <= 4 -> 0
                    offset <= 9 -> 1
                    else -> 2
                }).coerceIn(0, raw.length)
            },
        )
    }
}

/**
 * An invite opened from a link (intertune://together/<code>): asked once, then joined as a guest.
 * Handed the player rather than reading LocalPlayerConnection: it is shown where links are opened,
 * above where that is provided (the first two-phone run crashed on it, 17 Oct 2026).
 */
@Composable
fun TogetherInviteDialog(code: RoomCode, playerConnection: PlayerConnection?, onDone: () -> Unit) {
    val context = LocalContext.current
    playerConnection ?: return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Rounded.Group, contentDescription = null) },
        title = { Text(stringResource(R.string.together_join_title)) },
        // Held to a width: chips that fill the dialog stretched it to the edges of the screen.
        text = { CodeChips(code, Modifier.widthIn(max = 320.dp)) },
        confirmButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    playerConnection.service.startTogether(code, owner = false, relays = togetherRelays(context), name = togetherName(context))
                    onDone()
                }
            }) { Text(stringResource(R.string.together_join)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}
