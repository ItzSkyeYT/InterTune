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
 * Where to cast, and how loud: the receivers found on the Wi-Fi while this is open, with the one
 * that is playing and its volume at the top.
 *
 * A receiver can also be typed in by address, behind the plus: the emulator needs that (multicast
 * does not cross its NAT) and so does a network that blocks multicast, but it is nobody's first
 * choice and used to be the first thing on the page. The session keeps those, so they are listed
 * again next time.
 *
 * It stays open while a receiver is being reached, so one that does not answer says so here, and
 * it stays open once the music plays there, on the volume: a TV starts as loud as it was left.
 */
@Composable
private fun CastPicker(session: CastSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val problem by session.problem.collectAsState()
    // Searching only while the picker is open: SSDP goes out every ten seconds for as long as it runs.
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

    DefaultDialog(
        onDismiss = onDismiss,
        horizontalAlignment = Alignment.Start,
        icon = { Icon(if (casting != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, contentDescription = null) },
        title = {
            Text(
                if (casting != null) stringResource(R.string.cast_playing_on, casting.device.name)
                else stringResource(R.string.cast_to)
            )
        },
        buttons = {
            if (current != null) {
                TextButton(onClick = {
                    session.stop()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cast_stop))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss))
            }
        },
    ) {
        if (casting != null) ReceiverVolume()

        // What is going on, and on the right the way in for a receiver that was not found.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                when {
                    connecting != null -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.cast_connecting_to, connecting.device.name), style = MaterialTheme.typography.bodyMedium)
                    }
                    found.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.cast_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            IconButton(onClick = {
                adding = !adding
                addressInvalid = false
            }) {
                Icon(
                    imageVector = if (adding) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = stringResource(if (adding) R.string.dismiss else R.string.cast_add_by_address),
                )
            }
        }

        problem?.takeIf { connecting == null }?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                        .padding(horizontal = 16.dp)
                        .focusRequester(focus),
                )
                Text(
                    text = stringResource(R.string.cast_about),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        val devices = (listOfNotNull(current) + found + added).distinctBy { it.protocol to it.id }
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            items(devices, key = { "${it.protocol}:${it.id}" }) { device ->
                val isCurrent = current != null && current.protocol == device.protocol && current.id == device.id
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text(describe(device)) },
                    leadingContent = { Icon(iconOf(device), contentDescription = null) },
                    trailingContent = if (casting != null && isCurrent) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { session.castTo(device) },
                )
            }
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
