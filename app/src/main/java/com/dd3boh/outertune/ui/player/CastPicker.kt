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

/**
 * The cast button: the picker when tapped, and what casting is doing at a glance. Filled in the
 * accent colour while a receiver plays, faded while connecting.
 *
 * It sits in the empty space at the top right of the full player (DECISION.md), laid over it by
 * the caller, so no control around it moves or changes size. Nothing at all with
 * Unreleased.CASTING off.
 */
@Composable
fun CastButton(tint: Color, modifier: Modifier = Modifier) {
    if (!Unreleased.CASTING) return
    val playerConnection = LocalPlayerConnection.current ?: return
    val session = playerConnection.service.castSession ?: return
    val state by session.state.collectAsState()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    val casting = state as? CastSession.State.Casting
    val connecting = state as? CastSession.State.Connecting
    IconButton(onClick = { showPicker = true }, modifier = modifier) {
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
        CastPicker(session, onDismiss = { showPicker = false })
    }
}

/**
 * Where to cast: the receivers found on the Wi-Fi while this is open, and one typed in by address,
 * which the emulator needs (multicast does not cross its NAT) and so does a network that blocks
 * multicast. While casting it says where, and stops.
 *
 * It stays open while a receiver is being reached, so a receiver that does not answer says so
 * here, and closes by itself once the music plays there.
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

    // Closed once a receiver picked here plays, not by opening the picker while already casting.
    var picked by remember { mutableStateOf(false) }
    LaunchedEffect(state, picked) {
        if (picked && state is CastSession.State.Casting) onDismiss()
    }

    var address by rememberSaveable { mutableStateOf("") }
    var addressInvalid by remember { mutableStateOf(false) }
    val castTo: (CastDevice) -> Unit = { device ->
        picked = true
        session.castTo(device)
    }
    val addAddress = {
        val device = CastAddress.parse(address)
        if (device == null) {
            addressInvalid = true
        } else {
            discovery.add(device)
            castTo(device)
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
        if (connecting != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cast_connecting_to, connecting.device.name))
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

        val devices = (found + listOfNotNull(current)).distinctBy { it.protocol to it.id }
        if (found.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cast_searching), style = MaterialTheme.typography.bodyMedium)
            }
        }
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            items(devices, key = { "${it.protocol}:${it.id}" }) { device ->
                val isCurrent = current != null && current.protocol == device.protocol && current.id == device.id
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text(describe(device)) },
                    leadingContent = {
                        Icon(if (device.protocol == CastProtocol.FCAST) Icons.Rounded.Tv else Icons.Rounded.Speaker, contentDescription = null)
                    },
                    trailingContent = if (casting != null && isCurrent) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { castTo(device) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
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
            trailingIcon = {
                IconButton(onClick = addAddress, enabled = address.isNotBlank()) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.cast_add_by_address))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) addAddress() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Text(
            text = stringResource(R.string.cast_about),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** What kind of receiver it is, and where, for the line under its name. */
private fun describe(device: CastDevice): String {
    val kind = when (device.protocol) {
        CastProtocol.FCAST -> device.model ?: "FCast"
        CastProtocol.DLNA -> listOfNotNull("DLNA", device.model).joinToString(", ")
    }
    return "$kind, ${device.host}"
}
