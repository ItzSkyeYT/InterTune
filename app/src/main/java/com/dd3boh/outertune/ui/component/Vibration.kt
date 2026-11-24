/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

// The vibrator's constants are numbers, copied in at build time: nothing here asks the vibrator anything (Haptics does).
@file:SuppressLint("InlinedApi")

package com.dd3boh.outertune.ui.component

import android.annotation.SuppressLint
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.media3.common.Player

/**
 * The Vibration setting under Look and feel (Unreleased.HAPTICS_FEEL; his ask of 20 Nov 2026 on his S25U, "the haptics
 * aren't really noticeable... how custom can we make them?"). The shape is Essentials', his design reference, whose
 * Disabled, Enabled and Stronger are these three (experiments/features/design-language/essentials-study.md, part 4).
 */
enum class Vibration {
    /** None from the app at all. */
    OFF,

    /**
     * The app's own feel per action, as Strong, at about half its strength (his pick of 21 Nov 2026: on phones whose
     * own touch feedback is firm, the view's feedback that Light was felt as strong as Strong, to dvd and Ti-Die). Where
     * the app cannot play its own (before Android 11, no vibrator), the view's own feedback, as before. Android's Touch
     * feedback setting turns both off, as for every app.
     */
    LIGHT,

    /** The app's own, played through the vibrator as touch feedback, a feel per action ([Feel]). */
    STRONG;

    companion object {
        /** Strong for the trial: on his S25U Light is the gentlest Android has. */
        val DEFAULT = STRONG
    }
}

/** What an action feels like under Strong. */
enum class Feel {
    /** Every tap without a feel of its own: a crisp tick. */
    TICK,

    /** The volume landing on 100%, a swipe past its line: a click. */
    CLICK,

    /** Play and pause, a long press, the seek bar let go: a firm click. */
    FIRM,

    /** A like: two clicks. */
    DOUBLE,

    /** The seek bar's ticks while it is dragged: low and soft. */
    LOW_TICK,

    /** The seek bar reaching its start or its end: the volume's click, a tad softer (Ti-Die, #testers 23 Nov 2026). */
    EDGE,

    /**
     * A button turning something off, a like taken back: the pop after [DOUBLE]'s hit. A soft click, a little firmer than
     * the tap's tick, which already plays at its full strength (Ti-Die, #testers 23 Nov 2026: "increase the pop's
     * amplitude a little (without touching the hit's)").
     */
    POP,
}

/** What the vibrator is asked to play for one feel, worked out by [buzzFor]. */
sealed interface Buzz {
    /** One primitive of a composition, played [delay] ms after the one before it ends. */
    data class Step(val primitive: Int, val scale: Float, val delay: Int = 0)

    /** Nothing: the setting is Off. */
    data object None : Buzz

    /** Light's way: the haptic the app has always asked of the view. */
    data object Light : Buzz

    /** Primitives one after the other (VibrationEffect.startComposition, Android 11 and later). */
    data class Primitives(val steps: List<Step>) : Buzz

    /** One of Android's own effects (VibrationEffect.EFFECT_*), tuned by the phone's maker where it has them. */
    data class Predefined(val effect: Int) : Buzz

    /** Plain pulses of [length] ms, [gap] ms apart: the last resort, on a vibrator that offers nothing better. */
    data class Pulses(
        val count: Int,
        val length: Long,
        val gap: Long = 0,
        val amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE,
    ) : Buzz {
        /** The waveform's times: off for nothing, then on and off by turns. */
        fun timings(): LongArray = LongArray(count * 2) { i -> if (i == 0) 0L else if (i % 2 == 1) length else gap }

        fun amplitudes(): IntArray = IntArray(count * 2) { i -> if (i % 2 == 1) amplitude else 0 }
    }
}

/**
 * What the phone's vibrator can play, read once from it (Haptics).
 *
 * @param primitives the composition primitives it plays (VibrationEffect.Composition.PRIMITIVE_*)
 * @param effects its answer for each predefined effect asked about (Vibrator.VIBRATION_EFFECT_SUPPORT_*); one missing
 *   is one it did not say
 */
data class VibratorCaps(
    val sdk: Int,
    val hasVibrator: Boolean,
    val primitives: Set<Int>,
    val effects: Map<Int, Int>,
    val amplitudeControl: Boolean,
)

/** How strong Light is beside Strong: about half. */
const val LIGHT_SCALE = 0.5f

/** The faintest primitive Light plays: some vibrators play nothing at all below about this. */
const val LIGHT_FLOOR = 0.15f

/** The predefined effects a feel can be played with, the best first. */
fun predefinedFor(feel: Feel): List<Int> = when (feel) {
    // An effect has no strength to turn: the pop keeps the tick, the phone's click being the volume's whole one.
    Feel.TICK, Feel.LOW_TICK, Feel.POP -> listOf(VibrationEffect.EFFECT_TICK)
    Feel.CLICK, Feel.EDGE -> listOf(VibrationEffect.EFFECT_CLICK)
    Feel.FIRM -> listOf(VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_CLICK)
    Feel.DOUBLE -> listOf(VibrationEffect.EFFECT_DOUBLE_CLICK)
}

/**
 * The predefined effects Light falls back on where there are no primitives, a step lighter than Strong's: an effect
 * cannot be played softer. A like's double has no lighter effect: [buzzFor] plays it as soft pulses where it can.
 */
fun lighterPredefinedFor(feel: Feel): List<Int> = when (feel) {
    Feel.TICK, Feel.LOW_TICK, Feel.CLICK -> listOf(VibrationEffect.EFFECT_TICK)
    Feel.FIRM -> listOf(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK)
    Feel.DOUBLE -> listOf(VibrationEffect.EFFECT_DOUBLE_CLICK)
}

/**
 * What [feel] plays under [vibration] on a phone that can do [caps].
 *
 * Off is nothing. Strong is the brief's feel per action (experiments/features/haptics-feel/BRIEF.md), played with the
 * best the vibrator has: its primitives where it plays the ones needed, else its predefined effect (unless it says it
 * has none: one that cannot say gets it, and Android plays its own stand-in where the hardware has none), else plain
 * pulses of 20 ms. Light is the same at about half the strength: the primitives at [LIGHT_SCALE], a lighter effect
 * ([lighterPredefinedFor]), softer or shorter pulses. Below Android 11, where there are no primitives and no answer
 * about effects, and on a phone without a vibrator, both are the view's own feedback ([Buzz.Light]).
 */
fun buzzFor(feel: Feel, vibration: Vibration, caps: VibratorCaps): Buzz {
    if (vibration == Vibration.OFF) return Buzz.None
    if (caps.sdk < 30 || !caps.hasVibrator) return Buzz.Light
    val light = vibration == Vibration.LIGHT
    composition(feel, caps)?.let { return if (light) it.softer() else it }
    if (light && feel == Feel.DOUBLE && caps.amplitudeControl) return pulsesFor(feel, caps).softer(caps)
    val effects = if (light) lighterPredefinedFor(feel) else predefinedFor(feel)
    effects.firstOrNull { caps.effects[it] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO }?.let { return Buzz.Predefined(it) }
    return if (light) pulsesFor(feel, caps).softer(caps) else pulsesFor(feel, caps)
}

/** Plain pulses, the last resort, at Strong's strength. */
private fun pulsesFor(feel: Feel, caps: VibratorCaps): Buzz.Pulses = when (feel) {
    Feel.DOUBLE -> Buzz.Pulses(count = 2, length = 20, gap = 60)
    // Essentials' subtle stand-in, where the vibrator can play softly; only shorter where it cannot.
    Feel.LOW_TICK -> if (caps.amplitudeControl) Buzz.Pulses(count = 1, length = 10, amplitude = 50) else Buzz.Pulses(count = 1, length = 10)
    else -> Buzz.Pulses(count = 1, length = 20)
}

/** The same steps at [LIGHT_SCALE] of their strength, never under [LIGHT_FLOOR]. */
fun Buzz.Primitives.softer(): Buzz.Primitives =
    Buzz.Primitives(steps.map { it.copy(scale = (it.scale * LIGHT_SCALE).coerceAtLeast(LIGHT_FLOOR)) })

/** Half the amplitude where the vibrator can play softly, else a shorter pulse. */
fun Buzz.Pulses.softer(caps: VibratorCaps): Buzz.Pulses =
    if (caps.amplitudeControl) copy(amplitude = ((if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) 255 else amplitude) * LIGHT_SCALE).toInt().coerceAtLeast(1))
    else copy(length = (length * 0.6f).toLong().coerceAtLeast(8))

/** [feel] as primitives, where the phone plays the ones it needs. */
private fun composition(feel: Feel, caps: VibratorCaps): Buzz.Primitives? {
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    val lowTick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    val steps = when (feel) {
        Feel.TICK -> listOf(Buzz.Step(tick, 1f))
        Feel.CLICK, Feel.FIRM -> listOf(Buzz.Step(click, 1f))
        // Essentials' double: the second click the stronger, 60 ms after the first.
        Feel.DOUBLE -> listOf(Buzz.Step(click, 0.7f), Buzz.Step(click, 1f, delay = 60))
        // The low tick is Android 12's; before it, or on a phone without it, a softer tick.
        // Softer than at first, with twice the ticks (Ti-Die, 20 Nov 2026: "increase the frequency of dots but make them less intense").
        Feel.LOW_TICK -> if (caps.sdk >= 31 && lowTick in caps.primitives) listOf(Buzz.Step(lowTick, 0.35f)) else listOf(Buzz.Step(tick, 0.3f))
        // The seek bar's ends at three quarters of the volume's click (Ti-Die, 23 Nov 2026: "a tad" lower).
        Feel.EDGE -> listOf(Buzz.Step(click, 0.75f))
        // A tick plays no stronger than at 1, so a pop a little firmer is the next primitive up, a click, at half: under
        // either of the hit's two. Tick, then click is Android's own order too (HapticFeedbackVibrationProvider).
        Feel.POP -> listOf(Buzz.Step(click, 0.5f))
    }
    return if (steps.all { it.primitive in caps.primitives }) Buzz.Primitives(steps) else null
}

/**
 * A player button's feel under Strong, after Ti-Die (#testers, 21 Nov 2026: the like "does the haptics perfectly", copy
 * it to the buttons that do not open anything; the ones that do, and skip, "closer to how the pause button feels").
 * One that turns something on and off is a like: two clicks turning it on, the pop turning it off ([turnsOff]; the
 * tap's tick until Ti-Die asked for it a little firmer, 23 Nov 2026). One that opens something (a sheet, a menu,
 * Android's share sheet) or skips (null) is play and pause's firm click.
 */
fun buttonFeel(turnsOff: Boolean?): Feel = when (turnsOff) {
    null -> Feel.FIRM
    true -> Feel.POP
    false -> Feel.DOUBLE
}

/**
 * Repeat's feel under Strong, given the mode [from] which it is tapped. It goes off, all, one, off, and only the tap from
 * off turns it on: that one is the hit, the one into one ("loop once") and the one back off are the pop (Ti-Die,
 * #testers 23 Nov 2026: "it'd work better if it had the pop feel since it's not turning on").
 */
fun repeatFeel(from: Int): Feel = buttonFeel(turnsOff = from != Player.REPEAT_MODE_OFF)

/**
 * The feel of a haptic the app asks of Compose (FeltHaptics), as the player's buttons use them: Confirm is play and
 * pause, SegmentFrequentTick skip, shuffle and repeat under Light (Strong plays theirs as [buttonFeel] says), ContextClick
 * a tap.
 */
fun feelOf(type: HapticFeedbackType): Feel = when (type) {
    HapticFeedbackType.Confirm, HapticFeedbackType.LongPress -> Feel.FIRM
    HapticFeedbackType.SegmentTick, HapticFeedbackType.GestureEnd -> Feel.CLICK
    HapticFeedbackType.Reject -> Feel.DOUBLE
    HapticFeedbackType.TextHandleMove -> Feel.LOW_TICK
    else -> Feel.TICK
}
