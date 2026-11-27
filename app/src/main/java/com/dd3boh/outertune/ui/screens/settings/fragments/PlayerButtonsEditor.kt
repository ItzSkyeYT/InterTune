/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.player.PinMoves
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The box of the buttons the player does not show, as a drop target beside the places. */
private object NotShown

/** What one spot of a place's bar holds: a button that can move, room for one more, or the queue or the menu. */
private sealed interface Spot {
    data class Button(val pin: PlayerPin) : Spot
    data object Free : Spot
    data object Queue : Spot
    data object Menu : Spot
}

/** One way a button can go, offered by a tap, and as an accessibility action. Null [to] takes it off the player. */
private class Move(val label: String, val room: String?, val enabled: Boolean, val to: PinPlace?)

/**
 * The player buttons editor (Ti-Die's ask of 26 Nov 2026, mock 2 his pick): a box for each place of the style shown,
 * drawn like that bar of the player with its free spots and how many are free, then the buttons not shown as a grid of
 * tiles. A long press lifts a button to drag it into a place, from one place to another or back to Not shown; a full
 * place refuses it and its count shows why. A tap offers the same moves in a menu, and each button has them as
 * accessibility actions. Every move is [PinMoves.move], stored as the chips stored it ([onPinsChange]). The three-dots
 * button ends the bar of [menuIn], where the player has it (MenuPlaces.barOf), and no bar when that is none.
 */
@Composable
fun PlayerButtonsEditor(
    pins: Map<PinPlace, List<PlayerPin>>,
    places: List<PinPlace>,
    most: (PinPlace) -> Int,
    available: List<PlayerPin>,
    queueButton: Boolean,
    menuIn: PinPlace?,
    onPinsChange: (Map<PinPlace, List<PlayerPin>>) -> Unit,
    name: @Composable (PlayerPin) -> String,
    icon: @Composable (PlayerPin) -> Painter,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // The button held, and the finger, in the editor's own coordinates.
    var lifted by remember { mutableStateOf<PlayerPin?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    // The place that refused the last drop, red for a moment.
    var refused by remember { mutableStateOf<PinPlace?>(null) }
    var menuOf by remember { mutableStateOf<PlayerPin?>(null) }
    // Where things are, in the window: the editor, each button that can move, and each box. The boxes are taken as
    // they are at the lift, so a box growing or shrinking under the finger while it shows the drop cannot flicker.
    val editor = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val buttonBounds = remember { HashMap<PlayerPin, Rect>() }
    val boxBounds = remember { HashMap<Any, Rect>() }
    val boxesAtLift = remember { HashMap<Any, Rect>() }

    val currentPins by rememberUpdatedState(pins)
    val currentPlaces by rememberUpdatedState(places)
    val currentMost by rememberUpdatedState(most)
    val change by rememberUpdatedState(onPinsChange)

    fun inWindow(local: Offset): Offset? = editor[0]?.takeIf { it.isAttached }?.localToRoot(local)
    fun boxAt(local: Offset): Any? = inWindow(local)?.let { at -> boxesAtLift.entries.firstOrNull { it.value.contains(at) }?.key }
    fun moved(pin: PlayerPin, box: Any?): Map<PinPlace, List<PlayerPin>>? = when (box) {
        is PinPlace -> PinMoves.move(currentPins, currentPlaces, pin, box, currentMost)
        NotShown -> PinMoves.move(currentPins, currentPlaces, pin, null, currentMost)
        else -> currentPins
    }

    LaunchedEffect(refused) {
        if (refused != null) {
            delay(1600)
            refused = null
        }
    }

    // While a button is held over a box, the boxes show the drop: the button where it would land, or the box red.
    val hover = lifted?.let { boxAt(finger) }
    val preview = lifted?.let { pin -> hover?.let { moved(pin, it) } }
    val refusing = if (lifted != null && hover is PinPlace && preview == null) hover else refused
    val shown = preview ?: pins

    val putLabels = places.associateWith { stringResource(if (it == PinPlace.CONNECTED_ROW) R.string.player_pins_put_under_controls else R.string.player_pins_put_by_title) }
    val removeLabel = stringResource(R.string.player_pins_remove)
    val roomTexts = places.associateWith { roomText(PinMoves.free(pins[it].orEmpty(), most(it))) }

    fun movesOf(pin: PlayerPin): List<Move> {
        val from = PinMoves.placeOf(pins, places, pin)
        return places.filter { it != from }.map { Move(putLabels.getValue(it), roomTexts.getValue(it).takeIf { _ -> pin != PlayerPin.CAST }, PinMoves.fits(pins, it, pin, most(it)), it) } +
                listOfNotNull(if (from != null) Move(removeLabel, null, true, null) else null)
    }

    fun moveTo(pin: PlayerPin, to: PinPlace?) {
        menuOf = null
        PinMoves.move(pins, places, pin, to, most)?.let { if (it != pins) onPinsChange(it) }
    }

    @Composable
    fun Movable(pin: PlayerPin, content: @Composable () -> Unit) {
        val moves = movesOf(pin)
        Box(
            Modifier
                .onGloballyPositioned { buttonBounds[pin] = it.boundsInRoot() }
                .semantics {
                    customActions = moves.filter { it.enabled }.map { move -> CustomAccessibilityAction(move.label) { moveTo(pin, move.to); true } }
                }
                // A tap that ends a long press without a move is the press, not a tap.
                .clickable { if (lifted == null) menuOf = pin }
        ) {
            content()
            DropdownMenu(expanded = menuOf == pin && lifted == null, onDismissRequest = { menuOf = null }) {
                moves.forEach { move ->
                    DropdownMenuItem(
                        text = { Text(move.label) },
                        trailingIcon = if (move.room != null) {
                            { Text(move.room, style = MaterialTheme.typography.labelMedium) }
                        } else null,
                        enabled = move.enabled,
                        onClick = { moveTo(pin, move.to) },
                    )
                }
            }
        }
    }

    Box(
        modifier
            .onGloballyPositioned { editor[0] = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        val pin = inWindow(at)?.let { w -> buttonBounds.entries.firstOrNull { it.value.contains(w) }?.key }
                        if (pin != null) {
                            boxesAtLift.clear()
                            boxesAtLift.putAll(boxBounds)
                            menuOf = null
                            refused = null
                            finger = at
                            lifted = pin
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { pointer, amount ->
                        if (lifted != null) {
                            pointer.consume()
                            finger += amount
                        }
                    },
                    onDragEnd = {
                        val pin = lifted
                        if (pin != null) {
                            val box = boxAt(finger)
                            val after = moved(pin, box)
                            if (after == null) {
                                refused = box as PinPlace
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            } else if (after != currentPins) {
                                change(after)
                            }
                        }
                        lifted = null
                    },
                    onDragCancel = { lifted = null },
                )
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            places.forEach { place ->
                val chosen = shown[place].orEmpty().filter { it in available }
                val free = PinMoves.free(chosen, most(place))
                PlaceBox(
                    title = stringResource(if (place == PinPlace.CONNECTED_ROW) R.string.player_pins_under_controls else R.string.player_pins_by_title),
                    room = roomText(free),
                    taking = hover == place && preview != null,
                    refusing = refusing == place,
                    modifier = Modifier.onGloballyPositioned { boxBounds[place] = it.boundsInRoot() },
                ) {
                    // The bar as the player draws it: the queue first when it is a button, room for more further out
                    // from the menu, the buttons in the place's own order, and the menu last in the bar it stands in.
                    val queue: List<Spot> = if (queueButton && place != PinPlace.CONNECTED_TITLE) listOf(Spot.Queue) else emptyList()
                    val menu: List<Spot> = if (place == menuIn) listOf(Spot.Menu) else emptyList()
                    val spots: List<Spot> = queue + List(free) { Spot.Free } + chosen.map { Spot.Button(it) } + menu
                    PlaceBar(
                        spots = spots,
                        circles = place == PinPlace.CLASSIC,
                        centred = place == PinPlace.CONNECTED_ROW,
                        lifted = lifted,
                        name = name,
                        icon = icon,
                        movable = { pin, content -> Movable(pin, content) },
                    )
                }
            }

            val placed = places.flatMap { shown[it].orEmpty() }.toSet()
            val rest = available.filter { it !in placed }
            PlaceBox(
                title = stringResource(R.string.player_pins_not_shown),
                room = null,
                taking = hover == NotShown && lifted?.let { PinMoves.placeOf(pins, places, it) } != null,
                refusing = false,
                modifier = Modifier.onGloballyPositioned { boxBounds[NotShown] = it.boundsInRoot() },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                ) {
                    rest.forEach { pin ->
                        Movable(pin) { Tile(name(pin), icon(pin), ghost = pin == lifted) }
                    }
                }
            }
        }

        lifted?.let { pin ->
            val density = LocalDensity.current
            val w = 60.dp
            val h = 48.dp
            val red = refusing != null && refusing == hover
            val shape = RoundedCornerShape(16.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // Above the finger, so the finger never hides it.
                    .offset { with(density) { IntOffset((finger.x - w.toPx() / 2).roundToInt(), (finger.y - h.toPx() - 24.dp.toPx()).roundToInt()) } }
                    .size(w, h)
                    .shadow(8.dp, shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
                    .border(2.dp, if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, shape)
            ) {
                Icon(icon(pin), null, tint = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun roomText(free: Int): String =
    if (free <= 0) stringResource(R.string.player_pins_full) else pluralStringResource(R.plurals.player_pins_free, free, free)

/** A place, or Not shown: its name and its room on top, outlined, blue while it takes a drop and red while it refuses one. */
@Composable
private fun PlaceBox(
    title: String,
    room: String?,
    taking: Boolean,
    refusing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .border(
                width = if (taking || refusing) 2.dp else 1.dp,
                color = when {
                    refusing -> colors.error
                    taking -> colors.primary
                    else -> colors.outlineVariant
                },
                shape = shape,
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            if (room != null) {
                Text(
                    text = room,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (refusing) colors.error else colors.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.padding(top = 10.dp)) { content() }
    }
}

/**
 * A place's bar: Classic's circles, or Connected's segments joined into one bar, round at its two ends. By the title
 * it ends at the right as in the player, and under the controls it is centred. Each spot has its name under it.
 */
@Composable
private fun PlaceBar(
    spots: List<Spot>,
    circles: Boolean,
    centred: Boolean,
    lifted: PlayerPin?,
    name: @Composable (PlayerPin) -> String,
    icon: @Composable (PlayerPin) -> Painter,
    movable: @Composable (PlayerPin, @Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = if (circles) 8.dp else 2.dp
        // As wide as the box allows, and no wider than a thumb needs.
        val cell = min(72.dp, (maxWidth - gap * (spots.size - 1)) / spots.size.coerceAtLeast(1))
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap, if (centred) Alignment.CenterHorizontally else Alignment.End),
            modifier = Modifier.fillMaxWidth()
        ) {
            spots.forEachIndexed { i, spot ->
                val shape = if (circles) CircleShape else segmentShape(i == 0, i == spots.lastIndex)
                val faceWidth = if (circles) min(cell, 48.dp) else cell
                val faceHeight = if (circles) faceWidth else 44.dp
                when (spot) {
                    is Spot.Button -> movable(spot.pin) {
                        Labelled(cell, name(spot.pin), dim = spot.pin == lifted) {
                            Face(faceWidth, faceHeight, shape, icon(spot.pin), if (spot.pin == lifted) FaceLook.Ghost else FaceLook.Filled)
                        }
                    }
                    Spot.Free -> Labelled(cell, null, dim = false) { Face(faceWidth, faceHeight, shape, null, FaceLook.Free) }
                    Spot.Queue -> Labelled(cell, stringResource(R.string.queue), dim = false) {
                        Face(faceWidth, faceHeight, shape, rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic), FaceLook.Filled)
                    }
                    Spot.Menu -> Labelled(cell, stringResource(R.string.player_pins_menu), dim = false) {
                        Face(faceWidth, faceHeight, shape, rememberVectorPainter(Icons.Rounded.MoreVert), FaceLook.Filled)
                    }
                }
            }
        }
    }
}

/** A button not shown: its icon in an outlined tile, its name under it. */
@Composable
private fun Tile(name: String, icon: Painter, ghost: Boolean) {
    Labelled(76.dp, name, dim = ghost) {
        Face(56.dp, 44.dp, RoundedCornerShape(14.dp), icon, if (ghost) FaceLook.Ghost else FaceLook.Outlined)
    }
}

@Composable
private fun Labelled(width: Dp, label: String?, dim: Boolean, face: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        face()
        Text(
            // A free spot keeps the line, so every spot's face stands at the same height.
            text = label ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private enum class FaceLook { Filled, Outlined, Ghost, Free }

/** A button's face: filled when placed, outlined in the grid, dashed in the accent where the held one goes, dashed grey when free. */
@Composable
private fun Face(width: Dp, height: Dp, shape: Shape, icon: Painter?, look: FaceLook) {
    val colors = MaterialTheme.colorScheme
    val dash = when (look) {
        FaceLook.Ghost -> colors.primary
        FaceLook.Free -> colors.outline
        else -> null
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width, height)
            .then(
                when (look) {
                    FaceLook.Filled -> Modifier.background(colors.surfaceContainerHighest, shape)
                    FaceLook.Outlined -> Modifier.border(1.dp, colors.outlineVariant, shape)
                    else -> Modifier.dashed(dash ?: Color.Unspecified, shape)
                }
            )
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = when (look) {
                    FaceLook.Ghost -> colors.primary
                    FaceLook.Outlined -> colors.onSurfaceVariant
                    else -> colors.onSurface
                },
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun Modifier.dashed(color: Color, shape: Shape): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawOutline(
        outline = shape.createOutline(size, layoutDirection, this),
        color = color,
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

/** A segment of a connected bar, as the player's: round at the bar's own ends, nearly square where it meets the next. */
private fun segmentShape(first: Boolean, last: Boolean): Shape {
    val outer = 22.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}
