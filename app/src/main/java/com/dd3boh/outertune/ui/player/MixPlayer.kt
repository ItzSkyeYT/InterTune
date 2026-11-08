/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.constants.DynamicThemeKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.automix.MixOnScreen
import com.dd3boh.outertune.ui.theme.DefaultThemeColor
import com.dd3boh.outertune.ui.theme.LocalCoverColours
import com.dd3boh.outertune.ui.theme.extractGradientColors
import com.dd3boh.outertune.ui.theme.extractThemeSource
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player while a change plays as a mix (Unreleased.AUTOMIX; experiments/features/mix-player/
 * SPEC.md): both covers in the cover's box, sized by the music, the title and the seek bar on the
 * outgoing song until the incoming one takes over, and the colours and the background turning from
 * one cover's to the other's. MixLook works out how it looks; this follows the music.
 */
@Stable
class MixScreen internal constructor(
    private val player: Player,
    /** The change the service says plays as a mix (MusicService.mixOnScreen). */
    private val announced: State<MixOnScreen?>,
    /** The song the player names. */
    private val current: State<MediaMetadata?>,
    private val lyrics: State<Boolean>,
    private val failed: State<Boolean>,
) {
    /**
     * The change on screen: the one the service says plays as a mix, while the player plays the
     * song it brings in. Not with the lyrics up (no covers there) nor over a failed song: today's
     * player there.
     */
    val target: MixOnScreen? by derivedStateOf {
        announced.value?.takeIf { it.incoming.id == current.value?.id && !lyrics.value && !failed.value }
    }

    /** Whether a change playing as a mix brings in the song [id]: the strip of covers jumps to it rather than slide, the covers being one there. */
    fun bringsIn(id: String?): Boolean = id != null && announced.value?.incoming?.id == id

    /** The change [follow] follows, by its id. */
    private var followed by mutableLongStateOf(NONE)

    /** Where the incoming song is, in ms, as [follow] read it last: at each frame while the covers can be seen. */
    private var atNow by mutableLongStateOf(0L)

    /** The words' song as [follow] keeps it, swapped with the fade as the incoming song takes over. */
    private var followedOutgoing by mutableStateOf(false)

    /** The title's and the bar's opacity, which dips as they swap to the incoming song. */
    private val words = Animatable(1f)

    /** Where the incoming song is: as [follow] read it last, or for a change it has not started on, as the player says now. */
    private fun at(t: MixOnScreen): Long = if (followed == t.id) atNow else player.currentPosition

    /**
     * The title's, the buttons' and the bar's song: the outgoing one until the incoming one takes
     * over. Decided with the covers, from the same place in the music, on the very frame the player
     * names the incoming song: one frame of its title over the outgoing cover was seen on the
     * Pixel 5 (8 Nov 2026) while [follow] had not started yet.
     */
    val showsOutgoing: Boolean
        get() {
            val t = target ?: return false
            return if (followed == t.id) followedOutgoing else MixLook.showsOutgoing(t.heard(player.currentPosition), t.handover)
        }

    /** How much of [target] is heard, 0 to 1. */
    val heard: Float
        get() {
            val t = target ?: return 0f
            return t.heard(at(t))
        }

    /**
     * Whether the screen shows [target]: from its start until the incoming cover is whole again.
     * All of it counted in the music (MixLook.shows), so one first seen once it is over (the player
     * opened after it) is never shown.
     */
    val active: Boolean by derivedStateOf {
        val t = target ?: return@derivedStateOf false
        MixLook.shows(t, at(t))
    }

    /** [heard] to the nearest of MixLook.COLOUR_STEPS: what the colours and the background follow. */
    val stepped: Float by derivedStateOf { MixLook.stepped(heard) }

    /** The share heard last, kept after the change leaves the screen. */
    private var lastHeard = 0f

    /**
     * How far Living blur's colours are from the outgoing cover's to the incoming one's: the share
     * heard, and once the change has left the screen, where it was then, so the colours turn on
     * from there into the next cover's (LivingMotion.blendTo) rather than going back first.
     */
    val livingShare: Float get() = if (active) heard.also { lastHeard = it } else lastHeard

    /** The words' opacity: read where they are drawn. */
    val wordsAlpha: Float get() = words.value

    /** Where both covers are now. */
    fun covers(): MixLook.Covers {
        val t = target ?: return MixLook.Covers(MixLook.WHOLE, MixLook.WHOLE.copy(size = 0f, alpha = 0f))
        return MixLook.covers(t, at(t))
    }

    /**
     * Follows [next] while it plays, or with null puts today's player back at once. Where the
     * incoming song is, read at each frame ([frames]) or four times a second, moves the covers
     * (MixLook.covers), and the words swap as the incoming song takes over, until the covers are
     * one again.
     */
    internal suspend fun follow(next: MixOnScreen?, frames: () -> Boolean) {
        if (next == null) {
            followed = NONE
            followedOutgoing = false
            words.snapTo(1f)
            return
        }
        // What the screen showed before this started, taken over as it is: the words are set
        // before the change counts as followed, with nothing in between to draw a frame on.
        atNow = player.currentPosition
        followedOutgoing = MixLook.showsOutgoing(next.heard(atNow), next.handover)
        followed = next.id
        words.snapTo(1f)
        coroutineScope {
            var swap: Job? = null
            while (MixLook.shows(next, atNow)) {
                if (frames()) withFrameNanos { } else delay(LOOK_MS)
                atNow = player.currentPosition
                val outgoing = MixLook.showsOutgoing(next.heard(atNow), next.handover)
                if (outgoing != followedOutgoing && swap?.isActive != true) swap = launch { swapTo(outgoing) }
            }
        }
    }

    /** The title and the bar fade out, change song, and fade back in: Material's fade through. */
    private suspend fun swapTo(outgoing: Boolean) {
        words.animateTo(0f, tween(MixLook.SWAP_OUT_MS, easing = FastOutLinearInEasing))
        followedOutgoing = outgoing
        words.animateTo(1f, tween(MixLook.SWAP_MS - MixLook.SWAP_OUT_MS, easing = LinearOutSlowInEasing))
    }

    // What each cover gives the player: the theme's colours (LocalCoverColours) and the gradient
    // background's two colours, for the change announced, read before it comes on screen.
    private var coloursOf by mutableLongStateOf(NONE)
    private var outgoingColours by mutableStateOf<ColorScheme?>(null)
    private var incomingColours by mutableStateOf<ColorScheme?>(null)
    internal var outgoingGradient by mutableStateOf<List<Color>?>(null)
        private set
    internal var incomingGradient by mutableStateOf<List<Color>?>(null)
        private set

    /** Reads what each cover of [change] gives the player, as the app's theme and the gradient background read it. */
    internal suspend fun readColours(context: Context, change: MixOnScreen?, schemeOf: ((Color) -> ColorScheme)?) {
        coloursOf = NONE
        outgoingColours = null
        incomingColours = null
        outgoingGradient = null
        incomingGradient = null
        if (change == null) return
        val outgoing = coloursOfCover(context, change.outgoing)
        val incoming = coloursOfCover(context, change.incoming)
        outgoingColours = schemeOf?.invoke(outgoing.first)
        incomingColours = schemeOf?.invoke(incoming.first)
        outgoingGradient = outgoing.second
        incomingGradient = incoming.second
        coloursOf = change.id
    }

    /** The player's colours [stepped] of the way from the outgoing cover's to the incoming one's, or null for the app's own. */
    internal fun coloursNow(): Pair<ColorScheme, ColorScheme>? {
        val t = target ?: return null
        if (!active || coloursOf != t.id) return null
        val from = outgoingColours ?: return null
        val to = incomingColours ?: return null
        return from to to
    }

    /** The gradient background's two pairs of colours for [target], once both are read. */
    internal fun gradients(): Pair<List<Color>, List<Color>>? {
        val t = target ?: return null
        if (coloursOf != t.id) return null
        val from = outgoingGradient?.takeIf { it.size >= 2 } ?: return null
        val to = incomingGradient?.takeIf { it.size >= 2 } ?: return null
        return from to to
    }

    private companion object {
        const val NONE = -1L

        /** How often the share heard is read while no frame of the covers is drawn: paused, the player closed or out of view. */
        const val LOOK_MS = 250L
    }
}

/**
 * The player's screen for a change that plays as a mix, or null in a build without AutoMix, where
 * the player is exactly what it was. [lyrics] says whether the lyrics are up, [open] whether the
 * player is open over the app: only then are the covers moved at every frame.
 */
@Composable
fun rememberMixScreen(playerConnection: PlayerConnection, lyrics: State<Boolean>, open: () -> Boolean): MixScreen? {
    if (!Unreleased.AUTOMIX) return null
    val announced = (playerConnection.service.mixOnScreen ?: NOTHING_ANNOUNCED).collectAsState()
    val current = playerConnection.mediaMetadata.collectAsState()
    val error = playerConnection.error.collectAsState()
    val failed = remember(error) { derivedStateOf { error.value != null } }
    val screen = remember(playerConnection, announced, current, lyrics, failed) {
        MixScreen(playerConnection.player, announced, current, lyrics, failed)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isOpen by rememberUpdatedState(open)
    val target = screen.target
    LaunchedEffect(screen, target) {
        screen.follow(target) { isOpen() && playerConnection.player.isPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    }
    // Read as the service announces the change, a moment before the player names the incoming
    // song, so that the colours are there as it starts.
    val context = LocalContext.current
    val coverColours = LocalCoverColours.current
    val followsCovers by rememberPreference(DynamicThemeKey, defaultValue = true)
    val next = announced.value
    LaunchedEffect(screen, next?.id, coverColours, followsCovers) {
        screen.readColours(context, next, coverColours?.takeIf { followsCovers })
    }
    return screen
}

private val NOTHING_ANNOUNCED: StateFlow<MixOnScreen?> = MutableStateFlow(null)

/** A cover's theme colour, as the app takes it (MainActivity: its 100 px thumbnail), and the gradient background's two colours. */
private suspend fun coloursOfCover(context: Context, song: MediaMetadata): Pair<Color, List<Color>?> = withContext(coilCoroutine) {
    val model = song.getThumbnailModel(100, 100) ?: return@withContext DefaultThemeColor to null
    val bitmap = context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(model)
            .allowHardware(false)
            .build()
    ).image?.toBitmap() ?: return@withContext DefaultThemeColor to null
    bitmap.extractThemeSource() to bitmap.extractGradientColors()
}

/**
 * The player in its colours: the app's, or while a change plays as a mix, the outgoing cover's
 * turning into the incoming one's with the music (SPEC.md 3). The same theme around the player
 * either way, so nothing in it is made again as a change comes and goes.
 */
@Composable
fun MixColours(mix: MixScreen?, content: @Composable () -> Unit) {
    if (mix == null) return content()
    val app = MaterialTheme.colorScheme
    val pair = mix.coloursNow()
    val step = mix.stepped
    val colours = remember(app, pair, step) { pair?.let { (from, to) -> MixLook.blend(from, to, step) } ?: app }
    MaterialTheme(colorScheme = colours, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/**
 * The player's background while a change plays as a mix: the outgoing cover's Blur or Gradient
 * turning into the incoming one's with the music (SPEC.md 3), drawn whole over the usual one, as
 * that one would draw each of them. [base] is the colour under it, [overlay] the wash over it,
 * [artwork] and [gradient] the opacities the usual one draws with. Living blur turns by itself
 * (LivingBackground's blend). Nothing while no change is on screen.
 */
@Composable
internal fun MixBackdrop(
    mix: MixScreen,
    style: PlayerBackgroundStyle,
    base: Color,
    overlay: Color,
    artwork: Float,
    gradient: Float,
    /** The Blur background's radius in px, and the window it is stretched over (blurredCover). */
    blurRadius: Float,
    window: IntSize,
) {
    val change = mix.target ?: return
    if (!mix.active) return
    val step = mix.stepped
    when (style) {
        PlayerBackgroundStyle.BLUR -> {
            val context = LocalContext.current
            val (outgoingAlpha, incomingAlpha) = MixLook.backgroundAlphas(step, artwork)
            val outgoing = remember(change.outgoing, blurRadius, window) { blurredCover(context, change.outgoing, blurRadius, window) }
            val incoming = remember(change.incoming, blurRadius, window) { blurredCover(context, change.incoming, blurRadius, window) }
            Box(Modifier.fillMaxSize().background(base)) {
                AsyncImage(model = outgoing, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().alpha(outgoingAlpha))
                AsyncImage(model = incoming, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().alpha(incomingAlpha))
                Box(Modifier.fillMaxSize().background(overlay))
            }
        }
        PlayerBackgroundStyle.GRADIENT -> {
            val (from, to) = mix.gradients() ?: return
            val colours = remember(from, to, step) {
                when {
                    step <= 0f -> from
                    step >= 1f -> to
                    else -> List(minOf(from.size, to.size)) { lerp(from[it], to[it], step) }
                }
            }
            Box(Modifier.fillMaxSize().background(base)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colours), alpha = gradient))
                Box(Modifier.fillMaxSize().background(overlay))
            }
        }
        else -> Unit
    }
}

/**
 * One of the two covers in the cover's box while a change plays as a mix: laid out as the whole
 * box, and moved, sized, faded and shaped as [MixScreen.covers] has it at each frame, read here
 * so that the music moves it without composing anything.
 */
internal fun Modifier.mixCover(mix: MixScreen, outgoing: Boolean, morphs: CoverMorphs): Modifier = graphicsLayer {
    val covers = mix.covers()
    val spot = if (outgoing) covers.outgoing else covers.incoming
    scaleX = spot.size
    scaleY = spot.size
    translationX = (spot.x - 0.5f) * size.width
    translationY = (spot.y - 0.5f) * size.height
    alpha = spot.alpha
    shape = morphs.shape(spot.cookie)
    clip = true
}

/**
 * The picture inside one of those covers, after [mixCover]: enlarged about its middle so that it
 * fills the cookie as a square crop, and drawn whole in the rounded square as today
 * (MixLook.cropScale), so a video's 16:9 thumbnail keeps the cookie's scallops all round.
 * [aspect] is the picture's width over its height, 0 while it is not known.
 */
internal fun Modifier.mixCrop(mix: MixScreen, outgoing: Boolean, aspect: () -> Float): Modifier = graphicsLayer {
    val covers = mix.covers()
    val scale = MixLook.cropScale(aspect(), (if (outgoing) covers.outgoing else covers.incoming).cookie)
    scaleX = scale
    scaleY = scale
}

/** A loaded picture's width over its height, from Coil's state; null until it is loaded. */
internal fun aspectOf(state: AsyncImagePainter.State): Float? {
    val size = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize ?: return null
    return if (size.width > 0f && size.height > 0f && size.width.isFinite() && size.height.isFinite()) size.width / size.height else null
}
