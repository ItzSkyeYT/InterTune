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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.player.MenuPlace
import com.dd3boh.outertune.ui.player.PinMoves
import com.dd3boh.outertune.ui.player.PinPlace
import com.dd3boh.outertune.ui.player.PlayerPin
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.roundToInt

/** The box of the buttons the player does not show, as a drop target beside the places. */
internal object NotShown

/**
 * Classic's bottom right corner, where its three-dots button stands at the bottom, alone and in no bar: a box of its own
 * in the editor, for that button only. It refuses any other, and says why.
 */
internal object Corner

/** The three-dots button, held and tapped as a button is. */
private object MenuButton

/**
 * Not shown's rows (his ask of 27 Nov 2026: "only show everything when the user clicks on the show more as well as
 * putting 4 buttons per row"): four buttons to a row, as many as a bar of four, and only the first row until Show more.
 */
internal object NotShownRows {
    const val ROW = 4

    /** The buttons the box draws: all of [rest] when open, its first row when folded. */
    fun drawn(rest: List<PlayerPin>, open: Boolean): List<PlayerPin> = if (open) rest else rest.take(ROW)

    /** How many buttons Show more brings out. */
    fun folded(rest: List<PlayerPin>): Int = (rest.size - ROW).coerceAtLeast(0)

    /** Whether [pin], back in Not shown as [rest] has it, stands past the first row, folded away while the box is. */
    fun hides(rest: List<PlayerPin>, pin: PlayerPin): Boolean = rest.indexOf(pin) >= ROW
}

/** The room between two spots of a bar: Classic's circles stand apart, Connected's segments nearly touch. */
internal fun spotGap(circles: Boolean): Dp = if (circles) 8.dp else 2.dp

/** The width of each of [count] spots side by side in [width], [gap] apart: as wide as the box allows, and no wider than a thumb needs. */
internal fun spotWidth(width: Dp, count: Int, gap: Dp): Dp = min(72.dp, (width - gap * (count - 1)) / count.coerceAtLeast(1))

/**
 * The three-dots button in the editor (his ask of 27 Nov 2026: "make it be like any other button but cannot be removed"):
 * it goes between its two places, Top and Bottom, and never into Not shown. Its box at each is the bar it ends there, or
 * Classic's [Corner], where it stands at the bottom in no bar. Where it is, is the Top or Bottom choice itself.
 */
internal object MenuMoves {
    /** Its box at [at], in the style whose bars [bar] gives. */
    fun boxOf(at: MenuPlace, bar: (MenuPlace) -> PinPlace?): Any = bar(at) ?: Corner

    /** Where it stands if let go over [box]: the place whose box that is; none over Not shown or outside the boxes. */
    fun placeOver(box: Any?, bar: (MenuPlace) -> PinPlace?): MenuPlace? =
        if (box == null) null else MenuPlace.entries.firstOrNull { boxOf(it, bar) == box }

    /** Whether the style has a place for it in no bar, Classic's corner, which then has a box. */
    fun hasCorner(bar: (MenuPlace) -> PinPlace?): Boolean = MenuPlace.entries.any { bar(it) == null }

    /** Where a tap or TalkBack can send it from [at]: its other place, never off the player. */
    fun targets(at: MenuPlace): List<MenuPlace> = MenuPlace.entries.filter { it != at }
}

/** What one spot of a place's bar holds: a button that can move, room for one more, or the queue or the menu. */
private sealed interface Spot {
    data class Button(val pin: PlayerPin) : Spot
    data object Free : Spot
    data object Queue : Spot
    data object Menu : Spot
}

/** One way a button can go, offered by a tap, and as an accessibility action; as that action only when not [inMenu]. */
private class Move(val label: String, val room: String?, val enabled: Boolean, val inMenu: Boolean = true, val go: () -> Unit)

/**
 * The player buttons editor (Ti-Die's ask of 26 Nov 2026, mock 2 his pick): a box for each place of the style shown,
 * drawn like that bar of the player with its free spots dotted and Full once it has none, Classic's corner where its
 * three-dots button can stand at the bottom, then the buttons not shown as tiles, four to a row and folded to the first
 * row until Show more ([NotShownGrid]). A long press lifts a button to drag it into a place, from one place to another or
 * back to Not shown; a full place refuses it and its Full shows why. The three-dots button is lifted, tapped and moved the
 * same way, between [menuAt] and its other place ([MenuMoves]), and that move is its Top or Bottom place ([onMenuMove]);
 * Not shown refuses it and says why, and the corner refuses every other button and says why. A tap offers the same moves
 * in a menu, and each button has them as accessibility actions, with one more in Classic: under the controls, refused as
 * a drop on the corner is. Every move of a button is [PinMoves.move], stored as the chips stored it ([onPinsChange]).
 */
@Composable
fun PlayerButtonsEditor(
    pins: Map<PinPlace, List<PlayerPin>>,
    places: List<PinPlace>,
    most: (PinPlace) -> Int,
    available: List<PlayerPin>,
    queueButton: Boolean,
    menuAt: MenuPlace,
    menuBar: (MenuPlace) -> PinPlace?,
    onPinsChange: (Map<PinPlace, List<PlayerPin>>) -> Unit,
    onMenuMove: (MenuPlace) -> Unit,
    name: @Composable (PlayerPin) -> String,
    icon: @Composable (PlayerPin) -> Painter,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // What is held, a button or the three-dots button ([MenuButton]), and the finger, in the editor's own coordinates.
    var lifted by remember { mutableStateOf<Any?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    // The box that refused the last drop, red for a moment.
    var refused by remember { mutableStateOf<Any?>(null) }
    var menuOf by remember { mutableStateOf<Any?>(null) }
    // Not shown shows its first row until Show more. Not saved: the page opens with it folded every time.
    var unfolded by remember { mutableStateOf(false) }
    // Where things are, in the window: the editor, each button that can move, and each box. The boxes are taken as
    // they are at the lift, so a box growing or shrinking under the finger while it shows the drop cannot flicker.
    val editor = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val buttonBounds = remember { HashMap<Any, Rect>() }
    val boxBounds = remember { HashMap<Any, Rect>() }
    val boxesAtLift = remember { HashMap<Any, Rect>() }
    // The boxes on the page: the places of the style shown, Classic's corner where it has one, and Not shown. Only they
    // take a drop, never a box of the other style left from before a switch.
    val corner = MenuMoves.hasCorner(menuBar)
    val boxes: List<Any> = places + listOfNotNull(Corner.takeIf { corner }) + NotShown

    val currentPins by rememberUpdatedState(pins)
    val currentPlaces by rememberUpdatedState(places)
    val currentMost by rememberUpdatedState(most)
    val currentAvailable by rememberUpdatedState(available)
    val currentMenuAt by rememberUpdatedState(menuAt)
    val currentMenuBar by rememberUpdatedState(menuBar)
    val currentBoxes by rememberUpdatedState(boxes)
    val change by rememberUpdatedState(onPinsChange)
    val moveMenu by rememberUpdatedState(onMenuMove)

    fun inWindow(local: Offset): Offset? = editor[0]?.takeIf { it.isAttached }?.localToRoot(local)
    fun boxAt(local: Offset): Any? = inWindow(local)?.let { at -> boxesAtLift.entries.firstOrNull { it.value.contains(at) }?.key }
    fun moved(pin: PlayerPin, box: Any?): Map<PinPlace, List<PlayerPin>>? = when (box) {
        is PinPlace -> PinMoves.move(currentPins, currentPlaces, pin, box, currentMost)
        NotShown -> PinMoves.move(currentPins, currentPlaces, pin, null, currentMost)
        // Classic's corner holds the three-dots button alone.
        Corner -> null
        else -> currentPins
    }

    // A button put back in Not shown takes its own spot there, as it always has; when that spot is folded away, the
    // box opens, so the button is seen where it went rather than gone.
    fun showWhereItLands(after: Map<PinPlace, List<PlayerPin>>, pin: PlayerPin) {
        val rest = currentAvailable.filter { button -> currentPlaces.none { button in after[it].orEmpty() } }
        if (NotShownRows.hides(rest, pin)) unfolded = true
    }

    LaunchedEffect(refused) {
        if (refused != null) {
            // Classic's corner says why in a sentence: up long enough to read it once the button is let go.
            delay(if (refused == Corner) 5000 else 1600)
            refused = null
        }
    }

    // While something is held over a box, the boxes show the drop: where it would land, or the box red.
    val held = lifted
    val hover = held?.let { boxAt(finger) }
    val preview = (held as? PlayerPin)?.let { pin -> hover?.let { moved(pin, it) } }
    // The three-dots button goes to its other place only: Not shown refuses it.
    val menuPreview = if (held == MenuButton) MenuMoves.placeOver(hover, menuBar) else null
    val refusing = when {
        held is PlayerPin && hover != null && preview == null -> hover
        held == MenuButton && hover != null && menuPreview == null -> hover
        else -> refused
    }
    val shown = preview ?: pins
    val menuIn = MenuMoves.boxOf(menuPreview ?: menuAt, menuBar)

    val putByTitle = stringResource(R.string.player_pins_put_by_title)
    val putUnderControls = stringResource(R.string.player_pins_put_under_controls)
    val putLabels = places.associateWith { if (it == PinPlace.CONNECTED_ROW) putUnderControls else putByTitle }
    val removeLabel = stringResource(R.string.player_pins_remove)
    val roomTexts = places.associateWith { roomText(PinMoves.free(pins[it].orEmpty(), most(it))) }

    fun moveTo(pin: PlayerPin, to: PinPlace?) {
        menuOf = null
        PinMoves.move(pins, places, pin, to, most)?.let {
            if (it != pins) {
                onPinsChange(it)
                if (to == null) showWhereItLands(it, pin)
            }
        }
    }

    // TalkBack's move under the controls in Classic, refused as a drop on the corner is: red, the Reject haptic, and why,
    // which TalkBack says as the line comes.
    fun refuseInCorner() {
        menuOf = null
        refused = Corner
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    fun movesOf(pin: PlayerPin): List<Move> {
        val from = PinMoves.placeOf(pins, places, pin)
        return places.filter { it != from }.map { to -> Move(putLabels.getValue(to), roomTexts.getValue(to).takeIf { pin != PlayerPin.CAST }, PinMoves.fits(pins, to, pin, most(to))) { moveTo(pin, to) } } +
                // TalkBack's way to try what a drop on Classic's corner tries, and to hear why not. Not in the tap's menu,
                // where the corner is not a place a button goes; a drop there shows why.
                listOfNotNull(if (corner) Move(putUnderControls, null, enabled = true, inMenu = false) { refuseInCorner() } else null) +
                listOfNotNull(if (from != null) Move(removeLabel, null, true) { moveTo(pin, null) } else null)
    }

    // The three-dots button's: its other place, which always has room for it, and no Remove.
    fun menuMoves(): List<Move> = MenuMoves.targets(menuAt).map { to ->
        Move(if (to == MenuPlace.TOP) putByTitle else putUnderControls, null, true) {
            menuOf = null
            onMenuMove(to)
        }
    }

    @Composable
    fun Movable(button: Any, moves: List<Move>, content: @Composable () -> Unit) {
        // Folded away or gone to another box, it is under no finger any more: a long press where it was lifts nothing.
        DisposableEffect(button) { onDispose { buttonBounds.remove(button) } }
        Box(
            Modifier
                .onGloballyPositioned { buttonBounds[button] = it.boundsInRoot() }
                .semantics {
                    customActions = moves.filter { it.enabled }.map { move -> CustomAccessibilityAction(move.label) { move.go(); true } }
                }
                // A tap that ends a long press without a move is the press, not a tap.
                .clickable { if (lifted == null) menuOf = button }
        ) {
            content()
            DropdownMenu(expanded = menuOf == button && lifted == null, onDismissRequest = { menuOf = null }) {
                moves.filter { it.inMenu }.forEach { move ->
                    DropdownMenuItem(
                        text = { Text(move.label) },
                        trailingIcon = if (move.room != null) {
                            { Text(move.room, style = MaterialTheme.typography.labelMedium) }
                        } else null,
                        enabled = move.enabled,
                        onClick = move.go,
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
                        val button = inWindow(at)?.let { w -> buttonBounds.entries.firstOrNull { it.value.contains(w) }?.key }
                        if (button != null) {
                            boxesAtLift.clear()
                            boxesAtLift.putAll(boxBounds.filterKeys { it in currentBoxes })
                            menuOf = null
                            refused = null
                            finger = at
                            lifted = button
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
                        val box = boxAt(finger)
                        when (val button = lifted) {
                            is PlayerPin -> {
                                val after = moved(button, box)
                                if (after == null) {
                                    refused = box
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                } else if (after != currentPins) {
                                    change(after)
                                    if (box == NotShown) showWhereItLands(after, button)
                                }
                            }
                            MenuButton -> {
                                val to = MenuMoves.placeOver(box, currentMenuBar)
                                if (box != null && to == null) {
                                    refused = box
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                } else if (to != null && to != currentMenuAt) {
                                    moveMenu(to)
                                }
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
                    taking = hover == place && (preview != null || menuPreview != null),
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
                        movable = { pin, content -> Movable(pin, movesOf(pin), content) },
                        movableMenu = { content -> Movable(MenuButton, menuMoves(), content) },
                    )
                }
            }

            // Classic's corner under the controls, where its three-dots button stands at the bottom in no bar: a box of
            // its own after the bar by the title, the button at its right as in the player, or its spot while it is up.
            if (corner) {
                PlaceBox(
                    title = stringResource(R.string.player_pins_under_controls),
                    room = stringResource(R.string.player_pins_menu_only),
                    taking = hover == Corner && menuPreview != null,
                    refusing = refusing == Corner,
                    modifier = Modifier.onGloballyPositioned { boxBounds[Corner] = it.boundsInRoot() },
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        PlaceBar(
                            spots = listOf(if (menuIn == Corner) Spot.Menu else Spot.Free),
                            circles = true,
                            centred = false,
                            lifted = lifted,
                            name = name,
                            icon = icon,
                            movable = { pin, content -> Movable(pin, movesOf(pin), content) },
                            movableMenu = { content -> Movable(MenuButton, menuMoves(), content) },
                        )
                        // Why it refuses a button (his answer of 28 Nov 2026, "Say why on a refused drop"), in red beside its
                        // spot while it does, as Not shown says why it refuses the three dots. Always there and empty the rest
                        // of the time, so that its words coming are a change TalkBack says, after a drop or TalkBack's move.
                        Text(
                            text = if (refusing == Corner) stringResource(R.string.player_pins_classic_menu_only) else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(end = spotWidth(maxWidth, 1, spotGap(circles = true)) + spotGap(circles = true))
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }

            val placed = places.flatMap { shown[it].orEmpty() }.toSet()
            val rest = available.filter { it !in placed }
            PlaceBox(
                title = stringResource(R.string.player_pins_not_shown),
                // Why it refuses the three-dots button, in red while it does.
                room = if (refusing == NotShown) stringResource(R.string.player_pins_menu_stays) else null,
                taking = hover == NotShown && (held as? PlayerPin)?.let { PinMoves.placeOf(pins, places, it) } != null,
                refusing = refusing == NotShown,
                modifier = Modifier.onGloballyPositioned { boxBounds[NotShown] = it.boundsInRoot() },
            ) {
                NotShownGrid(
                    rest = rest,
                    open = unfolded,
                    circles = PinPlace.CLASSIC in places,
                    onOpenChange = { unfolded = it },
                    tile = { pin, width -> Movable(pin, movesOf(pin)) { Tile(width, name(pin), icon(pin), ghost = pin == lifted) } },
                )
            }
        }

        held?.let { button ->
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
                Icon(
                    if (button is PlayerPin) icon(button) else rememberVectorPainter(Icons.Rounded.MoreVert),
                    null,
                    tint = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * What a place says of its room: Full once it has none, and nothing while it has some, which its dotted spots show (Ti-Die,
 * 28 Nov 2026: a count of them only said again what the outlines do).
 */
@Composable
private fun roomText(free: Int): String? = if (free <= 0) stringResource(R.string.player_pins_full) else null

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
 * it ends at the right as in the player, and under the controls it is centred. Each spot has its name under it. The
 * buttons and the three-dots button can be lifted and tapped ([movable], [movableMenu]); the queue cannot.
 */
@Composable
private fun PlaceBar(
    spots: List<Spot>,
    circles: Boolean,
    centred: Boolean,
    lifted: Any?,
    name: @Composable (PlayerPin) -> String,
    icon: @Composable (PlayerPin) -> Painter,
    movable: @Composable (PlayerPin, @Composable () -> Unit) -> Unit,
    movableMenu: @Composable (@Composable () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = spotGap(circles)
        val cell = spotWidth(maxWidth, spots.size, gap)
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap, if (centred) Alignment.CenterHorizontally else Alignment.End),
            modifier = Modifier.fillMaxWidth()
        ) {
            spots.forEachIndexed { i, spot ->
                val shape = if (circles) CircleShape else segmentShape(i == 0, i == spots.lastIndex)
                val faceWidth = if (circles) min(cell, 48.dp) else cell
                val faceHeight = if (circles) faceWidth else 44.dp
                when (spot) {
                    // Keyed by the button, so the spot that knows where it stands moves with it.
                    is Spot.Button -> key(spot.pin) {
                        movable(spot.pin) {
                            Labelled(cell, name(spot.pin), dim = spot.pin == lifted) {
                                Face(faceWidth, faceHeight, shape, icon(spot.pin), if (spot.pin == lifted) FaceLook.Ghost else FaceLook.Filled)
                            }
                        }
                    }
                    Spot.Free -> Labelled(cell, null, dim = false) { Face(faceWidth, faceHeight, shape, null, FaceLook.Free) }
                    Spot.Queue -> Labelled(cell, stringResource(R.string.queue), dim = false) {
                        Face(faceWidth, faceHeight, shape, rememberVectorPainter(Icons.AutoMirrored.Rounded.QueueMusic), FaceLook.Filled)
                    }
                    Spot.Menu -> movableMenu {
                        Labelled(cell, stringResource(R.string.player_pins_menu), dim = lifted == MenuButton) {
                            Face(faceWidth, faceHeight, shape, rememberVectorPainter(Icons.Rounded.MoreVert), if (lifted == MenuButton) FaceLook.Ghost else FaceLook.Filled)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The buttons not shown, four to a row, each as wide as a spot of a bar of four and as far apart, so the page reads as
 * one: centred as a bar of four is, a short last row keeping the columns. Folded, the first row only; Show more brings
 * out the rest, Show fewer folds them away again (the app's own words, from Stats).
 */
@Composable
private fun NotShownGrid(
    rest: List<PlayerPin>,
    open: Boolean,
    circles: Boolean,
    onOpenChange: (Boolean) -> Unit,
    tile: @Composable (PlayerPin, Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = spotGap(circles)
        val cell = spotWidth(maxWidth, NotShownRows.ROW, gap)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
        ) {
            NotShownRows.drawn(rest, open).chunked(NotShownRows.ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { pin -> key(pin) { tile(pin, cell) } }
                    repeat(NotShownRows.ROW - row.size) { Spacer(Modifier.width(cell)) }
                }
            }
            val folded = NotShownRows.folded(rest)
            if (folded > 0) {
                val locale = LocalConfiguration.current.locales[0]
                TextButton(onClick = { onOpenChange(!open) }) {
                    Text(
                        if (open) stringResource(R.string.stats_show_fewer)
                        else pluralStringResource(R.plurals.stats_show_more, folded, NumberFormat.getIntegerInstance(locale).format(folded))
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }
        }
    }
}

/** A button not shown: its icon in an outlined tile, its name under it on up to three lines, in a cell [width] wide. */
@Composable
private fun Tile(width: Dp, name: String, icon: Painter, ghost: Boolean) {
    Labelled(width, name, dim = ghost, lines = 3) {
        Face(min(56.dp, width), 44.dp, RoundedCornerShape(14.dp), icon, if (ghost) FaceLook.Ghost else FaceLook.Outlined)
    }
}

@Composable
private fun Labelled(width: Dp, label: String?, dim: Boolean, lines: Int = 2, face: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        face()
        Text(
            // A free spot keeps the line, so every spot's face stands at the same height.
            text = label ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = lines,
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
