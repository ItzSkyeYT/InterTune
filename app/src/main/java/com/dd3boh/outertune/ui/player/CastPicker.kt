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
import androidx.compose.runtime.rememberUpdatedState
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
import com.dd3boh.outertune.cast.RecentReceivers
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
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
import com.dd3boh.outertune.ui.utils.LocalGlassStill
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
fun OutputButton(
    tint: Color,
    modifier: Modifier = Modifier,
    wide: Boolean = true,
    /** The player's backdrop when Liquid glass is on: the button is then frosted as the player's own buttons are. */
    backdrop: LayerBackdrop? = null,
    glassIntensity: Float = 1f,
) {
    if (!Unreleased.CASTING) return
    val playerConnection = LocalPlayerConnection.current ?: return
    val session = playerConnection.service.castSession ?: return
    // Battery use on Saver: frosted still, with nothing behind it blurred (LocalGlassStill).
    val still = LocalGlassStill.current
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
            color = if (backdrop != null) Color.Transparent else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier
                .requiredHeight(44.dp)
                .frosted(backdrop, glassIntensity, CircleShape, MaterialTheme.colorScheme.primary.copy(alpha = lerp(0.95f, 0.8f, glassIntensity.coerceIn(0f, 1f))), still),
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
            color = if (backdrop != null) Color.Transparent else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = modifier
                .fillMaxWidth()
                .requiredHeight(56.dp)
                .frosted(backdrop, glassIntensity, RoundedCornerShape(20.dp), MaterialTheme.colorScheme.primary.copy(alpha = lerp(0.95f, 0.8f, glassIntensity.coerceIn(0f, 1f))), still),
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
            color = if (backdrop != null) Color.Transparent else tint.copy(alpha = 0.12f),
            contentColor = tint,
            modifier = modifier
                .requiredHeight(44.dp)
                .frosted(backdrop, glassIntensity, CircleShape, tint.copy(alpha = 0.16f), still),
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

/**
 * Frosted as the player's own buttons are with Liquid glass on (Player.kt's glass buttons): the
 * blur and vibrancy of [backdrop] under the control's [fill], and no lens, highlight or shadow, whose
 * bright rim made small controls read as empty outlines. Nothing without a backdrop.
 */
private fun Modifier.frosted(backdrop: LayerBackdrop?, glassIntensity: Float, shape: Shape, fill: Color, still: Boolean): Modifier =
    if (backdrop == null) this
    // Still on Saver (LocalGlassStill): the fill alone, nothing behind it blurred.
    else if (still) this.clip(shape).background(fill, shape)
    else this
        .clip(shape)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(lerp(16f, 6f, glassIntensity.coerceIn(0f, 1f)).dp.toPx())
            },
            highlight = { null },
            shadow = { null },
        )
        .background(fill, shape)

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
        CastDiscovery(
            context.getSystemService<NsdManager>()!!,
            scope,
            network = { connectivity?.let { CastNetwork.wifi(it)?.network } },
            subnet = { connectivity?.let { CastNetwork.wifiSubnet(it) } },
        )
    }
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    val found by discovery.devices.collectAsState()
    val added by session.added.collectAsState()
    // Every receiver seen or cast to before is asked directly as the picker opens and listed as
    // soon as it answers, as a Chromecast's sender lists what it knows; one that is off never shows
    // (RecentReceivers). While the picker is open, everything listed is asked again every ten
    // seconds: a receiver switched off meanwhile leaves the list, a remembered one switched on
    // joins it. What the search finds is kept for next time.
    val recent by session.recent.collectAsState()
    var answered by remember { mutableStateOf(emptySet<CastDevice>()) }
    var silent by remember { mutableStateOf(emptySet<CastDevice>()) }
    LaunchedEffect(Unit) {
        var first = true
        while (true) {
            // The first time, only the remembered ones: what the search finds has just answered it.
            val asked = (session.recent.value + if (first) emptyList() else discovery.devices.value).distinct()
            val yes = mutableSetOf<CastDevice>()
            val no = mutableSetOf<CastDevice>()
            coroutineScope {
                for (device in asked) launch {
                    if (withContext(Dispatchers.IO) { RecentReceivers.answers(device) }) {
                        yes += device
                        if (first) answered = answered + device
                    } else {
                        no += device
                    }
                }
            }
            answered = yes
            silent = no
            first = false
            delay(RecentReceivers.RECHECK_MS)
        }
    }
    LaunchedEffect(found) { session.rememberSeen(found) }
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
    val receivers = RecentReceivers.listed(found, added, recent, answered, silent)
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

    // Pulled up past its height, the sheet follows the finger a little way, harder the higher it
    // goes, and settles back when let go: room added under the list, which raises the sheet by as
    // much (asked for on 18 Oct 2026). Pulled down, that room goes first, then the sheet as before.
    val density = LocalDensity.current
    val pullMost = with(density) { 72.dp.toPx() }
    var pulled by remember { mutableFloatStateOf(0f) }
    val pullUp = remember(pullMost) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f || pulled <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val taken = minOf(available.y, pulled)
                pulled -= taken
                return Offset(0f, taken)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val give = (1f - pulled / pullMost).coerceIn(0f, 1f)
                pulled = (pulled - available.y * 0.5f * give).coerceAtMost(pullMost)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled <= 0f) return Velocity.Zero
                animate(pulled, 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    pulled = value
                }
                return available
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        // The handle is drawn in the sheet's content instead: the library's lit up like a button
        // when held. The sheet is dragged by any part of it, as before.
        dragHandle = null,
        // In the middle of a wide window only the top and the bottom of the screen concern it: see BottomSheetMenu.
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(pullUp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
        ) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            NowPlayingHeader()
            // What plays, lit, and the row is its volume: slid along, as Android's own output
            // switcher has it (his pick of 14 Oct 2026, design A). The receiver's while casting,
            // this phone's otherwise.
            val receiverVolume = rememberReceiverVolume()
            val phoneVolume = rememberPhoneVolume(phoneName)
            if (current == null) {
                OutputRow(icon = phoneIcon, name = phoneName, line = null, lit = true, onClick = null, volume = phoneVolume)
            } else {
                OutputRow(
                    icon = iconOf(current),
                    name = current.name,
                    line = if (connecting != null) stringResource(R.string.cast_connecting_to, current.name) else describe(current),
                    lit = true,
                    busy = connecting != null,
                    onClick = null,
                    volume = receiverVolume.takeIf { casting != null },
                )
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
            if (pulled > 0f) Spacer(Modifier.height(with(density) { pulled.toDp() }))
        }
    }
}

/** How loud a place plays, as the lit row shows and sets it: [level] from 0 to 1, read out as [words]. */
private class OutputVolume(val level: Float, val words: String, val set: (Float) -> Unit)

/**
 * One place the music can play, lit for the one that plays. With [volume] the lit row is a slider
 * as well: lit as far as the volume goes, and a drag or a tap along it sets it.
 */
@Composable
private fun OutputRow(
    icon: ImageVector,
    name: String,
    line: String?,
    lit: Boolean,
    onClick: (() -> Unit)?,
    busy: Boolean = false,
    volume: OutputVolume? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    val sliding = lit && volume != null
    // The gestures read the volume as it is now: keyed on the volume itself they restarted at every
    // step it moved, and a drag set it once and stopped.
    val currentVolume by rememberUpdatedState(volume)
    // Where the finger is while it is down: the fill follows it exactly, between the steps the
    // device has, and glides to the device's own level once the finger lifts or a key moves it.
    var held by remember { mutableStateOf<Float?>(null) }
    val glided by animateFloatAsState(volume?.level ?: 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "volume")
    val shown = held ?: glided
    val fill = MaterialTheme.colorScheme.primary
    Surface(
        shape = shape,
        color = when {
            sliding -> fill.copy(alpha = 0.45f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
            lit -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        contentColor = when {
            sliding -> MaterialTheme.colorScheme.onPrimary
            lit -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (sliding) Modifier
                    // The row takes the finger the moment it lands and keeps it until it lifts: a
                    // drag on a real phone is never quite level, and with the usual drag detectors
                    // the sheet took the first up or down movement for a swipe to close it (his
                    // S25U, 14 Oct 2026). Only how far along the row the finger is counts.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            held = (down.position.x / size.width).coerceIn(0f, 1f)
                            currentVolume?.set(held!!)
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                held = (change.position.x / size.width).coerceIn(0f, 1f)
                                currentVolume?.set(held!!)
                            }
                            held = null
                        }
                    }
                    .semantics {
                        contentDescription = volume!!.words
                        progressBarRangeInfo = ProgressBarRangeInfo(volume.level, 0f..1f, steps = 0)
                        setProgress { asked -> volume.set(asked.coerceIn(0f, 1f)); true }
                    }
                else Modifier
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .then(if (sliding) Modifier.drawBehind { drawRect(fill, size = size.copy(width = size.width * shown)) } else Modifier)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
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
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** The song playing, small, at the top of the sheet: what is about to be sent somewhere. */
@Composable
private fun NowPlayingHeader() {
    val connection = LocalPlayerConnection.current ?: return
    val metadata by connection.mediaMetadata.collectAsState()
    val song = metadata ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
    ) {
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * How loud the receiver is, while one plays: through the player's own device volume, which is the
 * receiver's then (RemotePlayer), so the row, the volume keys and the receiver's own remote all
 * move the same number. Null for a receiver whose volume cannot be set (a fixed one, or DLNA
 * without a volume service), and while nothing is cast.
 */
@Composable
private fun rememberReceiverVolume(): OutputVolume? {
    val player = LocalPlayerConnection.current?.player ?: return null
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
    if (!remote || most <= 0) return null
    return OutputVolume(level.toFloat() / most, stringResource(R.string.cast_volume)) { asked ->
        val whole = (asked * most).roundToInt().coerceIn(0, most)
        if (whole != level) {
            level = whole
            player.setDeviceVolume(whole, 0)
        }
    }
}

/**
 * How loud this phone plays music, the same number its volume keys move, followed as they move it.
 * Read out as [words], the phone's name in the row.
 */
@Composable
private fun rememberPhoneVolume(words: String): OutputVolume? {
    val context = LocalContext.current
    val audio = remember { context.getSystemService<AudioManager>() } ?: return null
    val most = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(audio) {
        val keys = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        // Not a documented broadcast, but the one the system sends when any volume changes.
        ContextCompat.registerReceiver(context, keys, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(keys) }
    }
    if (most <= 0) return null
    return OutputVolume(level.toFloat() / most, words) { asked ->
        val whole = (asked * most).roundToInt().coerceIn(0, most)
        if (whole != level) {
            level = whole
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, whole, 0) }
        }
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
