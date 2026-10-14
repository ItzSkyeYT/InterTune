/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.cast.CastAddress
import com.dd3boh.outertune.cast.CastDevice
import com.dd3boh.outertune.cast.CastDiscovery
import com.dd3boh.outertune.cast.CastNetwork
import com.dd3boh.outertune.cast.CastProtocol
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.playback.CastSession
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import com.dd3boh.outertune.ui.utils.LocalLandscape
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow

/**
 * The cast button: the picker when tapped, and what casting is doing at a glance. Filled in the
 * accent colour while a receiver plays, faded while connecting.
 *
 * It sits in the empty space at the top right of the full player, laid over it by the caller, so
 * no control around it moves or changes size. Nothing at all with Unreleased.CASTING off.
 */
@Composable
fun CastButton(tint: Color, modifier: Modifier = Modifier) {
    if (!Unreleased.CASTING) return
    val playerConnection = LocalPlayerConnection.current ?: return
    val session = playerConnection.service.castSession ?: return
    val state by session.state.collectAsState()
    val showPicker by session.pickerOpen.collectAsState()

    val casting = state as? CastSession.State.Casting
    val connecting = state as? CastSession.State.Connecting
    IconButton(onClick = { session.pickerOpen.value = true }, modifier = modifier) {
        Icon(
            imageVector = if (casting != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = when {
                casting != null -> stringResource(R.string.cast_playing_on, casting.device.name)
                connecting != null -> stringResource(R.string.cast_connecting_to, connecting.device.name)
                else -> stringResource(R.string.cast)
            },
            tint = when {
                casting != null -> MaterialTheme.colorScheme.primary
                connecting != null -> tint.copy(alpha = 0.5f)
                else -> tint
            },
        )
    }

    if (showPicker) {
        CastPicker(session, onDismiss = { session.pickerOpen.value = false })
    }
}

/**
 * Where the music is coming out, under the player's controls, and the way to send it elsewhere.
 *
 * Day to day it is a quiet pill that names what Android is playing through: this phone, the
 * headphones by their own name, a cable. While a receiver plays it is a bar as wide as the seek
 * bar, in the accent colour, with the receiver's name: the shape changes when something is
 * different, and is small when all there is to say is "This phone". Either opens the sheet.
 *
 * The app does not switch between the phone's own outputs, which is Android's to do, so the pill
 * only says what the system says. Nothing at all with Unreleased.CASTING off.
 */
@Composable
fun OutputButton(tint: Color, modifier: Modifier = Modifier, wide: Boolean = true) {
    if (!Unreleased.CASTING) return
    val playerConnection = LocalPlayerConnection.current ?: return
    val session = playerConnection.service.castSession ?: return
    val state by session.state.collectAsState()
    val showPicker by session.pickerOpen.collectAsState()
    val casting = state as? CastSession.State.Casting
    val connecting = state as? CastSession.State.Connecting
    val local = rememberLocalOutput()
    val open = { session.pickerOpen.value = true }

    if (casting != null && !wide) {
        // Laid over a corner, on a phone on its side: the pill, in the bar's colour.
        Surface(
            onClick = open,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier.requiredHeight(44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 18.dp),
            ) {
                Icon(iconOf(casting.device), contentDescription = null, modifier = Modifier.size(22.dp))
                Text(casting.device.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else if (casting != null) {
        Surface(
            onClick = open,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier
                .fillMaxWidth()
                .requiredHeight(56.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(iconOf(casting.device), contentDescription = null)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp)
                ) {
                    Text(casting.device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.cast_casting),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
        }
    } else {
        Surface(
            onClick = open,
            shape = CircleShape,
            color = tint.copy(alpha = 0.12f),
            contentColor = tint,
            modifier = modifier.requiredHeight(44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 18.dp),
            ) {
                if (connecting != null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
                    Text(stringResource(R.string.cast_connecting_to, connecting.device.name), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Icon(
                        imageVector = when (local.kind) {
                            LocalOutput.Kind.PHONE -> Icons.Rounded.Smartphone
                            LocalOutput.Kind.BLUETOOTH, LocalOutput.Kind.WIRED -> Icons.Rounded.Headphones
                            LocalOutput.Kind.OTHER -> Icons.Rounded.Speaker
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = local.name ?: stringResource(
                            when (local.kind) {
                                LocalOutput.Kind.WIRED -> R.string.output_wired
                                LocalOutput.Kind.OTHER -> R.string.output_cable
                                else -> R.string.output_this_phone
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showPicker) {
        CastPicker(session, onDismiss = { session.pickerOpen.value = false })
    }
}

/** [LocalOutput] as it is now, and again whenever something is plugged in, paired or taken away. */
@Composable
private fun rememberLocalOutput(): LocalOutput {
    val context = LocalContext.current
    val audio = remember { context.getSystemService<AudioManager>() }
    fun read() = LocalOutput.of(
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { it.type to it.productName?.toString().orEmpty() }
    )
    var output by remember { mutableStateOf(read()) }
    DisposableEffect(audio) {
        val watch = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                output = read()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                output = read()
            }
        }
        audio?.registerAudioDeviceCallback(watch, null)
        onDispose { audio?.unregisterAudioDeviceCallback(watch) }
    }
    return output
}

/**
 * Where the music plays, and how loud: a sheet from the bottom, under the output button that opens
 * it. The device that plays stands first and lit, with its volume while it is a receiver; under it
 * the others, this phone among them while casting, which is how casting stops, as on Spotify; and
 * at the foot, quietly, the search and the way in for a receiver typed in by address.
 *
 * It was a dialog in the middle of the screen, with a title, a line that searched, a plus at the
 * top and Dismiss and Stop casting as words at the foot: "looks quite bad" (14 Oct 2026). Three
 * looks for this were drawn for him (experiments/features/casting/sheet-designs): this is the shape
 * all three share, and his pick sets the style of the rows.
 *
 * Searching runs only while it is open: SSDP goes out every ten seconds for as long as it runs. It
 * stays open while a receiver is being reached, so one that does not answer says so here, and once
 * the music plays there, on the volume: a TV starts as loud as it was left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastPicker(session: CastSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val problem by session.problem.collectAsState()
    val discovery = remember {
        val connectivity = context.getSystemService<ConnectivityManager>()
        CastDiscovery(context.getSystemService<NsdManager>()!!, scope, network = { connectivity?.let { CastNetwork.wifi(it)?.network } })
    }
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    val found by discovery.devices.collectAsState()
    val added by session.added.collectAsState()
    val local = rememberLocalOutput()
    val landscape = LocalLandscape.current

    var adding by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var addressInvalid by remember { mutableStateOf(false) }
    val addAddress = {
        val device = CastAddress.parse(address)
        if (device == null) {
            addressInvalid = true
        } else {
            session.rememberAdded(device)
            address = ""
            adding = false
            session.castTo(device)
        }
    }

    val casting = state as? CastSession.State.Casting
    val connecting = state as? CastSession.State.Connecting
    val current = casting?.device ?: connecting?.device
    val receivers = (found + added).distinctBy { it.protocol to it.id }
        .filterNot { current != null && it.protocol == current.protocol && it.id == current.id }
    val phoneName = local.name ?: stringResource(
        when (local.kind) {
            LocalOutput.Kind.WIRED -> R.string.output_wired
            LocalOutput.Kind.OTHER -> R.string.output_cable
            else -> R.string.output_this_phone
        }
    )
    val phoneIcon = when (local.kind) {
        LocalOutput.Kind.PHONE -> Icons.Rounded.Smartphone
        LocalOutput.Kind.BLUETOOTH, LocalOutput.Kind.WIRED -> Icons.Rounded.Headphones
        LocalOutput.Kind.OTHER -> Icons.Rounded.Speaker
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        // In the middle of a wide window only the top and the bottom of the screen concern it: see BottomSheetMenu.
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            // What plays, lit, with its volume while it is a receiver.
            if (current == null) {
                OutputRow(icon = phoneIcon, name = phoneName, line = null, lit = true, onClick = null)
            } else {
                OutputRow(
                    icon = iconOf(current),
                    name = current.name,
                    line = if (connecting != null) stringResource(R.string.cast_connecting_to, current.name) else describe(current),
                    lit = true,
                    busy = connecting != null,
                    onClick = null,
                ) {
                    if (casting != null) ReceiverVolume()
                }
            }
            problem?.takeIf { connecting == null }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // The other places it can play: this phone, which is how casting stops, and the receivers.
            if (current != null) {
                OutputRow(icon = phoneIcon, name = phoneName, line = null, lit = false, onClick = {
                    session.stop()
                    onDismiss()
                })
            }
            receivers.forEach { device ->
                OutputRow(icon = iconOf(device), name = device.name, line = describe(device), lit = false, onClick = { session.castTo(device) })
            }

            // At the foot, quietly: the search, and the way in by address.
            if (found.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.cast_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        adding = !adding
                        addressInvalid = false
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(if (adding) Icons.Rounded.Close else Icons.Rounded.Add, contentDescription = null)
                Text(stringResource(R.string.cast_add_by_address), style = MaterialTheme.typography.bodyLarge)
            }
            AnimatedVisibility(visible = adding) {
                Column {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            addressInvalid = false
                        },
                        label = { Text(stringResource(R.string.cast_add_by_address)) },
                        placeholder = { Text(stringResource(R.string.cast_address_hint)) },
                        singleLine = true,
                        isError = addressInvalid,
                        supportingText = if (addressInvalid) {
                            { Text(stringResource(R.string.cast_address_invalid)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) addAddress() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    Text(
                        text = stringResource(R.string.cast_about),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** One place the music can play, lit for the one that plays, with [under] beneath it there. */
@Composable
private fun OutputRow(
    icon: ImageVector,
    name: String,
    line: String?,
    lit: Boolean,
    onClick: (() -> Unit)?,
    busy: Boolean = false,
    under: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = if (lit) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (lit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    line?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = LocalContentColor.current.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            under()
        }
    }
}

/**
 * How loud the receiver is, to set from here: the phone's volume keys set it too while casting,
 * but nothing on screen said so, and a TV comes on as loud as it was left. Nothing for a receiver
 * whose volume cannot be set (a fixed one, or DLNA without a volume service).
 *
 * Through the player's own device volume, which is the receiver's while it plays there
 * (RemotePlayer), so the slider, the keys and the receiver's own remote all move the same number.
 */
@Composable
private fun ReceiverVolume() {
    val player = LocalPlayerConnection.current?.player ?: return
    var most by remember { mutableIntStateOf(player.deviceInfo.maxVolume) }
    var remote by remember { mutableStateOf(player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) }
    var level by remember { mutableIntStateOf(player.deviceVolume) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                level = volume
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                most = deviceInfo.maxVolume
                remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                level = player.deviceVolume
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    if (!remote || most <= 0) return
    val volumeWords = stringResource(R.string.cast_volume)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = when {
                level == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                level * 2 < most -> Icons.AutoMirrored.Rounded.VolumeDown
                else -> Icons.AutoMirrored.Rounded.VolumeUp
            },
            contentDescription = null,
        )
        Slider(
            value = level.toFloat(),
            onValueChange = { asked ->
                val whole = asked.roundToInt().coerceIn(0, most)
                if (whole != level) {
                    level = whole
                    player.setDeviceVolume(whole, 0)
                }
            },
            valueRange = 0f..most.toFloat(),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = volumeWords },
        )
    }
}

/** What kind of receiver it is, and where, for the line under its name. */
private fun describe(device: CastDevice): String {
    val kind = when (device.protocol) {
        CastProtocol.FCAST -> device.model ?: "FCast"
        CastProtocol.DLNA -> listOfNotNull("DLNA", device.model).joinToString(", ")
        // A Chromecast's model is often just "Chromecast", which needs saying only once.
        CastProtocol.CHROMECAST -> listOfNotNull("Chromecast", device.model?.takeIf { !it.equals("Chromecast", ignoreCase = true) }).joinToString(", ")
    }
    // Where it is only when that is all there is to tell two apart: one typed in by address is named by it.
    return if (device.name == device.host) kind else "$kind, ${device.host}"
}

/** A screen for FCast receivers and Chromecasts that have one, a speaker for DLNA and Cast speakers. */
private fun iconOf(device: CastDevice): ImageVector = when (device.protocol) {
    CastProtocol.FCAST -> Icons.Rounded.Tv
    CastProtocol.DLNA -> Icons.Rounded.Speaker
    CastProtocol.CHROMECAST -> if (device.audioOnly) Icons.Rounded.Speaker else Icons.Rounded.Tv
}
