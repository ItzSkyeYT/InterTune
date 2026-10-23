/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.walkthrough.SETTINGS_CLOSER_LOOK
import com.dd3boh.outertune.ui.screens.walkthrough.Tour
import com.dd3boh.outertune.ui.screens.walkthrough.TourTargets
import com.dd3boh.outertune.ui.screens.walkthrough.screen
import com.dd3boh.outertune.ui.utils.LocalLandscape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a setting is: the screen it is on, and the mark its row carries (see tourTarget, and
 * settingRow for the marks search finds rows by).
 *
 * @param fold the heading of the fold the row is in, opened on the way (SettingsClickToReveal)
 * @param instead the mark of the row to bring and light when [target] is not on its screen: the
 *   switch a setting hangs from, while it is off
 */
data class Jump(val target: String, val route: String, @StringRes val fold: Int? = null, val instead: String? = null)

/**
 * The settings an explanation can lead to (Unreleased.EXPLANATION_LINKS).
 *
 * An explanation names other settings: "It needs "Tidy Home rows" on." Named in quotation marks,
 * such a name is a way to the setting: a tap closes the explanation, opens the screen the setting
 * is on, scrolls to its row and flashes it, as a phone's own settings do for a search result.
 *
 * Where each is comes from the tour, which already knows the screen and the row of every setting
 * it stops at, and titles its stops with the string the row is titled with. The few rows below
 * are named in explanations and not stopped at by the tour, and carry a mark for this alone. A
 * choice of a setting leads to the setting.
 */
object SettingJumps {
    const val TIDY_HOME_ROWS = "jump_tidy_home_rows"
    const val RANK_WITH_LISTENING = "jump_rank_with_listening"
    const val FAMILIARITY = "jump_familiarity"
    const val SIMILAR_SOURCE = "jump_similar_source"
    const val CLEAR_LISTEN_HISTORY = "jump_clear_listen_history"

    private fun stop(id: String) = SETTINGS_CLOSER_LOOK.single { it.id == "closer_$id" }.let { Jump(it.targetId!!, it.screen) }

    /** A row, as the string resource its title is written with, and where it is. */
    val ROWS: List<Pair<Int, Jump>> by lazy {
        SETTINGS_CLOSER_LOOK.mapNotNull { stop -> stop.targetId?.let { stop.title to Jump(it, stop.screen) } } + listOf(
            R.string.tidy_home_rows to Jump(TIDY_HOME_ROWS, Tour.ROUTE_RECOMMENDATIONS),
            R.string.rank_with_listening to Jump(RANK_WITH_LISTENING, Tour.ROUTE_RECOMMENDATIONS),
            R.string.familiarity to Jump(FAMILIARITY, Tour.ROUTE_RECOMMENDATIONS),
            R.string.similar_source to Jump(SIMILAR_SOURCE, Tour.ROUTE_RECOMMENDATIONS),
            R.string.clear_listen_history to Jump(CLEAR_LISTEN_HISTORY, Tour.ROUTE_PRIVACY),
        )
    }

    /** A choice of a setting, which leads to the setting. */
    val CHOICES: List<Pair<Int, Jump>> by lazy {
        val quickPicksSource = stop("quick_picks_source")
        val spatialAudio = stop("spatial_audio")
        val similarSource = Jump(SIMILAR_SOURCE, Tour.ROUTE_RECOMMENDATIONS)
        listOf(
            R.string.recommendations_engine_title to quickPicksSource,
            R.string.recommendations_row_try_both to quickPicksSource,
            R.string.quick_picks_source_youtube to quickPicksSource,
            R.string.quick_picks_source_library to quickPicksSource,
            R.string.spatial_audio_headphones to spatialAudio,
            R.string.spatial_audio_surround to spatialAudio,
            R.string.similar_source_youtube to similarSource,
            R.string.similar_source_lastfm to similarSource,
        )
    }

    /** Every name that leads somewhere. */
    val NAMED: List<Pair<Int, Jump>> get() = ROWS + CHOICES
}

// A name in straight quotation marks, or in guillemets with the space French sets inside them,
// breaking or not. Never across a line, never empty, and not a stretch of text that only happens
// to lie between two marks: a name does not begin or end with a space.
private val QUOTED = Regex("\"(?! )([^\"\\n]{1,80}?)(?<! )\"|«[ \\u00A0\\u202F]([^«»\\n]{1,80}?)[ \\u00A0\\u202F]»")

/** Where the names an explanation writes in quotation marks stand in [text], each without its marks. */
internal fun quotedNames(text: String): List<IntRange> =
    QUOTED.findAll(text).mapNotNull { (it.groups[1] ?: it.groups[2])?.range }.toList()

/**
 * The names of [body] that are links, each with where it leads. [named] is every name that leads
 * somewhere and [rows] the ones that are a row's own title, both as the app writes them now.
 *
 * [own] is the title the explanation stands under. When that is a row's title, a name leading to
 * that very row is left as text: it would flash the row the reader has just come from. A title
 * that is only a choice's name, as the group "Best recommendations" is, is nobody's own row, and
 * took every link to "Quick picks source" with it while it was read as one.
 */
internal fun linkedNames(body: String, own: String, named: Map<String, Jump>, rows: Map<String, Jump>): List<Pair<IntRange, Jump>> {
    val ownRow = rows[own]
    return quotedNames(body).mapNotNull { range -> named[body.substring(range)]?.takeIf { it != ownRow }?.let { range to it } }
}

/** [body] with every name that leads somewhere made a link: see [linkedNames]. */
@Composable
fun explanationWithLinks(body: String, own: String, onJump: (Jump) -> Unit): AnnotatedString {
    // The names are in the app's language, which the resources follow when it changes.
    val resources = LocalResources.current
    val named = remember(resources) { SettingJumps.NAMED.associate { (name, jump) -> resources.getString(name) to jump } }
    val rows = remember(resources) { SettingJumps.ROWS.associate { (name, jump) -> resources.getString(name) to jump } }
    val jumpTo by rememberUpdatedState(onJump)
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return remember(body, own, named, rows, style) {
        buildAnnotatedString {
            var from = 0
            for ((range, jump) in linkedNames(body, own, named, rows)) {
                append(body.substring(from, range.first))
                withLink(LinkAnnotation.Clickable("setting", style) { jumpTo(jump) }) { append(body.substring(range)) }
                from = range.last + 1
            }
            append(body.substring(from))
        }
    }
}

/** The jump that was asked for, the row that is being flashed, and the row a search result lit. */
object SettingJump {
    var asked by mutableStateOf<Jump?>(null)
        private set

    /** Whether [asked] is a search result's: see [show]. */
    internal var searched = false
        private set
    internal var flashing by mutableStateOf<String?>(null)

    /** The row a search result has lit, which draws the light itself (settingRow), and how bright it is. */
    internal var lit by mutableStateOf<String?>(null)
    internal val light = Animatable(0f)

    /** The heading of the fold to open on the way to a row, as it is written, while a jump is on its way. */
    var unfolding by mutableStateOf<String?>(null)
        internal set

    /** A link in an explanation: the screen opens over the one the link was on, and the row flashes. */
    fun go(jump: Jump) {
        searched = false
        asked = jump
    }

    /**
     * A search result: the screen opens as from the list, in the list's place in the two panes,
     * and the row is lit for a moment, as a linked song is on its album.
     */
    fun show(jump: Jump) {
        searched = true
        asked = jump
    }

    internal fun made() {
        asked = null
    }
}

// How long a row may take to report itself once its screen is asked for. One that is not on this
// install, head tracking on a phone without it for instance, never does, and the screen is where
// the jump then ends.
private const val ARRIVE_MS = 3_000L

// The screen slides in before its rows are where they will stay.
private const val SETTLE_MS = 350L

private const val FLASH_ALPHA = 0.22f

// How long a row stays lit before it fades, and how long the fade takes: the album's for a linked song.
private const val LIGHT_HELD_MS = 1_200L
private const val LIGHT_FADE_MS = 900

// Once the row a setting hangs from is on the screen, how long the setting itself is given to
// arrive: a fold opening, or a row that comes a frame after the one above it.
private const val INSTEAD_MS = 700L

/**
 * The row the jump ends on: its own, or, when that is not on the screen, the one named
 * [Jump.instead]; null when neither arrives. A row that is not on this install, head tracking on
 * a phone without it for instance, never does, and the screen is where the jump then ends.
 */
private suspend fun arrived(jump: Jump): String? {
    val target = jump.target
    val instead = jump.instead
    if (instead == null) {
        return target.takeIf { withTimeoutOrNull(ARRIVE_MS) { snapshotFlow { TourTargets.known(target) }.first { it } } == true }
    }
    val first = withTimeoutOrNull(ARRIVE_MS) {
        snapshotFlow { if (TourTargets.known(target)) target else instead.takeIf { TourTargets.known(it) } }.first { it != null }
    } ?: return null
    if (first == target) return target
    val came = withTimeoutOrNull(INSTEAD_MS) { snapshotFlow { TourTargets.known(target) }.first { it } } == true
    return if (came) target else instead
}

/**
 * Makes the jump that was asked for, and draws the flash. Beside the tour's overlay, for the same
 * two reasons: the rows report where they are in the root's coordinates, and out here the flash
 * is over the navigation bar's level and outside the glass.
 */
@Composable
fun SettingJumpHost(navController: NavController) {
    val jump = SettingJump.asked
    val room = with(LocalDensity.current) { 96.dp.toPx() }
    // A search result's row comes to rest a third of the way into the window, as a linked song
    // does on its album, and not against the bar along the bottom.
    val third = with(LocalDensity.current) { LocalLandscape.current.windowHeight.toPx() } / 3f
    val resources = LocalResources.current
    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        val searched = SettingJump.searched
        try {
            // A link opens over where the explanation was, so Back returns there, as from any link.
            // In the two panes the screen opens on the right, over the one the link was on, and the
            // list stays. A search result opens as the list opens a screen.
            if (navController.currentDestination?.route != jump.route) navController.toSettings(jump.route, over = !searched)
            SettingJump.unfolding = jump.fold?.let { resources.getString(it) }
            val row = arrived(jump)
            if (row != null) {
                delay(SETTLE_MS)
                TourTargets.bring(row, if (searched) maxOf(room, third) else room)
                if (searched) SettingJump.lit = row else SettingJump.flashing = row
            }
        } finally {
            // Not when another jump has been asked for in the meantime, which is now on its way.
            if (SettingJump.asked == jump) {
                SettingJump.unfolding = null
                SettingJump.made()
            }
        }
    }

    // A search result's light, drawn by the row itself: on at once, held, then let go, as the album
    // lights a linked song.
    val lit = SettingJump.lit
    LaunchedEffect(lit) {
        if (lit == null) return@LaunchedEffect
        SettingJump.light.snapTo(1f)
        delay(LIGHT_HELD_MS)
        SettingJump.light.animateTo(0f, tween(LIGHT_FADE_MS))
        if (SettingJump.lit == lit) SettingJump.lit = null
    }

    val row = SettingJump.flashing ?: return
    val light = remember(row) { Animatable(0f) }
    LaunchedEffect(row) {
        // Twice, a light over the row that comes and goes.
        repeat(2) {
            light.animateTo(FLASH_ALPHA, tween(180))
            light.animateTo(0f, tween(420))
        }
        if (SettingJump.flashing == row) SettingJump.flashing = null
    }
    val rect = TourTargets[row] ?: return
    val colour = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.fillMaxSize()) {
        val inset = 8.dp.toPx()
        drawRoundRect(
            color = colour.copy(alpha = light.value),
            topLeft = Offset(rect.left + inset, rect.top),
            size = Size((rect.width - 2 * inset).coerceAtLeast(0f), rect.height),
            cornerRadius = CornerRadius(16.dp.toPx()),
        )
    }
}
