/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A change that plays as a mix, as the player's screen shows it (experiments/features/mix-player/
 * SPEC.md): both songs, and where the change lies in the incoming one. The player plays the
 * incoming song from the change's start while the outgoing one's end sounds from the stage, so how
 * much of the change is heard is the player's position in the incoming song against these
 * ([heard]): a pause, the speed or a jump inside it need nothing more.
 */
class MixOnScreen(
    /** The change, as the controller counts them: another change has another id. */
    val id: Long,
    /** The song going out: its title, artists and cover. */
    val outgoing: MediaMetadata,
    /** Its length in ms, as the player had it. */
    val outgoingLengthMs: Long,
    /** Where it was, in ms, as the incoming song came in. */
    val outgoingFromMs: Long,
    /** The song coming in, which the player plays from the change's start. */
    val incoming: MediaMetadata,
    /** Where the incoming song came in, in ms: 0, or the bar a change brings it in on. */
    val entryMs: Long,
    /** From the incoming song's entry to the outgoing song gone, in ms. */
    val lengthMs: Long,
    /** Where the incoming song takes over, in ms into the change: where its gain passes the outgoing one's. */
    val handoverMs: Long,
    /** Both songs' gains across the change as its plan has them, or null for an equal-power pair. */
    val gains: MixGains?,
) {
    /** How much of the change is heard with the incoming song at [positionMs], 0 to 1. */
    fun heard(positionMs: Long): Float = ((positionMs - entryMs).toFloat() / lengthMs).coerceIn(0f, 1f)

    /** Where the incoming song takes over, as a share of the change. */
    val handover: Float get() = (handoverMs.toFloat() / lengthMs).coerceIn(0f, 1f)

    /** Where the outgoing song is with the incoming one at [positionMs]: where it was as the change began, and the time since. */
    fun outgoingAt(positionMs: Long): Long =
        (outgoingFromMs + (positionMs - entryMs).coerceAtLeast(0)).coerceIn(0, outgoingLengthMs.coerceAtLeast(0))

    /** The change in a line, for the log. The songs' ids go round the templates, never in them (MixFormatTemplatesTest). */
    fun describe(): String =
        outgoing.id + " from %.1f s into ".format(java.util.Locale.ROOT, outgoingFromMs / 1000.0) +
            incoming.id + " from %.1f s, %.1f s, the incoming song takes over at %.1f s, ".format(java.util.Locale.ROOT, entryMs / 1000.0, lengthMs / 1000.0, handoverMs / 1000.0) +
            if (gains != null) "the plan's gains" else "equal power"

    companion object {
        /** The shortest change shown with two covers: they take 1.1 s to come apart and as long to come together. */
        const val SHORTEST_MS = 2_200L

        /**
         * The change [plan] plays, as the screen shows it, or null where it shows none: a gapless
         * plan, or a change shorter than [SHORTEST_MS]. [tailStartMs] is where the end held for it
         * begins in the outgoing song; every frame of the plan counts from there.
         */
        fun of(
            id: Long,
            plan: MixPlan,
            tailStartMs: Long,
            outgoing: MediaMetadata,
            outgoingLengthMs: Long,
            incoming: MediaMetadata,
        ): MixOnScreen? {
            if (plan.isGapless || plan.sampleRate <= 0) return null
            val rate = plan.sampleRate.toDouble()
            val lengthMs = Math.round((plan.aGone - plan.bStart) * 1000.0 / rate)
            if (lengthMs < SHORTEST_MS) return null
            val gains = MixGains.of(plan)
            val handover = gains?.handover() ?: MixGains.EQUAL_POWER_HANDOVER
            val length = outgoingLengthMs.takeIf { it > 0 } ?: (outgoing.duration * 1000L)
            val from = tailStartMs + Math.round(plan.aMap.tailAt(plan.bStart.toDouble()) * 1000.0 / rate)
            return MixOnScreen(
                id = id,
                outgoing = outgoing,
                outgoingLengthMs = length,
                outgoingFromMs = if (length > 0) from.coerceIn(0, length) else from.coerceAtLeast(0),
                incoming = incoming,
                entryMs = Math.round(plan.bEntry * 1000.0 / rate),
                lengthMs = lengthMs,
                handoverMs = Math.round(handover.toDouble() * lengthMs),
                gains = gains,
            )
        }
    }
}

/**
 * Which change the player's screen shows, as the stage and the player tell of it: put up as its
 * blend starts, kept into the song it brings in, and taken off at once when anything drops the
 * blend part way (SPEC.md, Limits). The controller hands each event on, on its main thread.
 */
class MixScreenState(private val say: (String) -> Unit = {}) {
    private val _shown = MutableStateFlow<MixOnScreen?>(null)
    val shown: StateFlow<MixOnScreen?> = _shown.asStateFlow()

    /** Whether the player has still to name the song the change on screen brings in. */
    private var awaitsSong = false

    /** Whether the blend on screen was let go by a jump inside it, and may be taken up again. */
    var resumePending = false
        private set

    /** The change [mix] starts to play as a mix, the player playing [playing]. */
    fun show(mix: MixOnScreen, playing: String?) {
        awaitsSong = playing != mix.incoming.id
        resumePending = false
        _shown.value = mix
        say("the player shows both covers: ${mix.describe()}")
    }

    /** Takes the change off at once: the one [id] names, or with none whichever is up. */
    fun hide(id: Long?, why: String) {
        val now = _shown.value ?: return
        if (id != null && now.id != id) return
        resumePending = false
        _shown.value = null
        say("the player shows one cover: $why")
    }

    /** The player names the song playing now, [id]: the change goes on into the song it brings in, and no further. */
    fun songStarted(id: String?) {
        val now = _shown.value ?: return
        if (awaitsSong && id == now.incoming.id) awaitsSong = false else hide(now.id, "another song started")
    }

    /** A jump the listener made, the stage still in a blend ([blendPlaying]) or not. One after the blend shows the one song from there. */
    fun jumped(blendPlaying: Boolean) {
        if (!blendPlaying) hide(null, "a jump after the blend")
    }

    /** The stage let go of the end held for change [id]. True when the screen waits to see whether its blend is taken up again. */
    fun dropped(id: Long): Boolean {
        if (_shown.value?.id != id) return false
        resumePending = true
        return true
    }

    /**
     * What the stage said of change [id], [resumable] whether a blend it let go can be taken up
     * again (HeldTail.resumable). A jump it cannot take up again, the next song found to play
     * differently from its copy, a fade out, a failure: the covers go at once. Taken up again, they stay.
     */
    fun stageSaid(id: Long, text: String, resumable: Boolean) {
        if (_shown.value?.id != id) return
        when {
            text.startsWith("picked up again") -> resumePending = false
            text.startsWith("dropped") -> if (resumable) resumePending = true else hide(id, "the blend was let go ($text)")
            text.startsWith("not picked up again") || text.startsWith("the next song plays differently") ||
                text.startsWith("aborted") || text.startsWith("failed") -> hide(id, text)
        }
    }

    /**
     * A look at a blend let go by a jump: [blendPlaying] whether the stage has taken it up again,
     * [playedMs] how far the song has played since the jump. Once it has played [RESUME_WAIT_MS]
     * on with no blend, the covers go. False when there is nothing more to wait for.
     */
    fun resumeLook(id: Long, blendPlaying: Boolean, playedMs: Long): Boolean {
        if (_shown.value?.id != id || !resumePending) return false
        if (blendPlaying) {
            resumePending = false
            return false
        }
        if (playedMs > RESUME_WAIT_MS) {
            hide(id, "the blend was not taken up again after a jump")
            return false
        }
        return true
    }

    companion object {
        /** How far past a jump the song may play before the covers go, with no blend taken up again. */
        const val RESUME_WAIT_MS = 1_000L
    }
}

/**
 * Both songs' gains across a change, as its plan has them: at [STEPS] + 1 even points from the
 * incoming song's first frame heard to the outgoing song gone, and along a straight line between.
 */
class MixGains(private val outgoing: FloatArray, private val incoming: FloatArray) {

    init {
        require(outgoing.size == STEPS + 1 && incoming.size == STEPS + 1)
    }

    /** The outgoing song's gain with [heard] of the change heard, 0 to 1. */
    fun outgoingAt(heard: Float): Float = at(outgoing, heard)

    /** The incoming song's gain with [heard] of the change heard, 0 to 1. */
    fun incomingAt(heard: Float): Float = at(incoming, heard)

    /** Where the incoming song's gain first passes the outgoing one's, as a share of the change: 1 where it never does. */
    fun handover(): Float {
        for (i in 0..STEPS) {
            val now = incoming[i] - outgoing[i]
            if (now <= 0f) continue
            if (i == 0) return 0f
            // Between the last point where it had not and this one, where the two lines cross.
            val before = incoming[i - 1] - outgoing[i - 1]
            return (i - 1 + before / (before - now)) / STEPS
        }
        return 1f
    }

    private fun at(gains: FloatArray, heard: Float): Float {
        val position = heard.coerceIn(0f, 1f) * STEPS
        val i = position.toInt().coerceAtMost(STEPS - 1)
        val f = position - i
        return gains[i] + (gains[i + 1] - gains[i]) * f
    }

    companion object {
        const val STEPS = 64

        /** Where an equal-power pair crosses: cos(x π/2) = sin(x π/2) half way. */
        const val EQUAL_POWER_HANDOVER = 0.5f

        /** The outgoing song's gain in an equal-power pair, with [heard] of the change heard. */
        fun equalPowerOut(heard: Float): Float = cos(heard.coerceIn(0f, 1f) * PI / 2).toFloat()

        /** The incoming song's. */
        fun equalPowerIn(heard: Float): Float = sin(heard.coerceIn(0f, 1f) * PI / 2).toFloat()

        /**
         * [plan]'s gains from its song change to the outgoing song gone, or null where it has none
         * of its own: a change on bands or parts has its songs' parts rendered ahead, with each
         * band's moves in them, and hands over gains that stay at 1.
         */
        fun of(plan: MixPlan): MixGains? {
            if (plan.gainA.ramps.isEmpty() && plan.gainB.ramps.isEmpty()) return null
            val from = plan.bStart.toDouble()
            val to = plan.aGone.toDouble()
            fun sampled(gain: (Double) -> Double) = FloatArray(STEPS + 1) { i -> gain(from + (to - from) * i / STEPS).toFloat().coerceIn(0f, 1f) }
            return MixGains(sampled(plan.gainA::at), sampled(plan.gainB::at))
        }
    }
}
