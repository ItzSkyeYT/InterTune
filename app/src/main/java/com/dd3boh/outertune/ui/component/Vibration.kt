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
enum class Vibration(
    /** Its place on the slider: 0 is Off, 1 to 5 the five steps. */
    val step: Int,
    /** How much of Strong's strength a step plays where the vibrator has amplitude control. */
    val strength: Float,
) {
    /** None from the app at all. */
    OFF(0, 0f),

    /** The gentlest step, the app's own at a quarter of Strong's strength. */
    FAINT(1, 0.25f),

    /** Step 2, and what "Light" always was: the view's own feedback, which Android's Touch feedback setting turns off, as for every app. */
    LIGHT(2, 0.4f),

    /** Step 3, the app's own at half of Strong's strength. */
    MEDIUM(3, 0.5f),

    /** Step 4, three quarters. */
    FIRM(4, 0.75f),

    /** Step 5, and what "Strong" always was: the app's own, played through the vibrator as touch feedback, a feel per action ([Feel]). */
    STRONG(5, 1f);

    companion object {
        /** Strong for the trial: on his S25U Light is the gentlest Android has. */
        val DEFAULT = STRONG

        /** The step [step] of the slider, 0 to 5; anything else is the nearest one. */
        fun ofStep(step: Int): Vibration = entries[step.coerceIn(0, entries.lastIndex)]
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

/** The predefined effects a feel can be played with, the best first. */
fun predefinedFor(feel: Feel): List<Int> = when (feel) {
    // An effect has no strength to turn: the pop keeps the tick, the phone's click being the volume's whole one.
    Feel.TICK, Feel.LOW_TICK, Feel.POP -> listOf(VibrationEffect.EFFECT_TICK)
    Feel.CLICK, Feel.EDGE -> listOf(VibrationEffect.EFFECT_CLICK)
    Feel.FIRM -> listOf(VibrationEffect.EFFECT_HEAVY_CLICK, VibrationEffect.EFFECT_CLICK)
    Feel.DOUBLE -> listOf(VibrationEffect.EFFECT_DOUBLE_CLICK)
}

/**
 * What [feel] plays under [vibration] on a phone that can do [caps].
 *
 * Off is nothing, Light today's, the steps between it and Strong gentler than Strong (see [scaled] and [gentler]). Strong is the brief's feel per action (experiments/features/haptics-feel/BRIEF.md),
 * played with the best the vibrator has: its primitives where it plays the ones needed, else its predefined effect
 * (unless it says it has none: one that cannot say gets it, and Android plays its own stand-in where the hardware has
 * none), else plain pulses of 20 ms. Below Android 11, where there are no primitives and no answer about effects, and
 * on a phone without a vibrator, Strong is Light's way.
 */
fun buzzFor(feel: Feel, vibration: Vibration, caps: VibratorCaps): Buzz {
    when (vibration) {
        Vibration.OFF -> return Buzz.None
        Vibration.LIGHT -> return Buzz.Light
        else -> Unit
    }
    if (caps.sdk < 30 || !caps.hasVibrator) return Buzz.Light
    val strong = strongBuzz(feel, caps)
    if (vibration == Vibration.STRONG) return strong
    // The steps under Strong: scaled where the vibrator has amplitude control, else gentler effects one rung down each.
    return if (caps.amplitudeControl) scaled(strong, feel, vibration.strength) else gentler(feel, vibration, caps)
}

/** Strong's own buzz for [feel]: the best the vibrator has (see [buzzFor]). */
private fun strongBuzz(feel: Feel, caps: VibratorCaps): Buzz {
    composition(feel, caps)?.let { return it }
    predefinedFor(feel).firstOrNull { caps.effects[it] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO }?.let { return Buzz.Predefined(it) }
    return when (feel) {
        Feel.DOUBLE -> Buzz.Pulses(count = 2, length = 20, gap = 60)
        // Essentials' subtle stand-in, where the vibrator can play softly; only shorter where it cannot.
        Feel.LOW_TICK -> if (caps.amplitudeControl) Buzz.Pulses(count = 1, length = 10, amplitude = 50) else Buzz.Pulses(count = 1, length = 10)
        else -> Buzz.Pulses(count = 1, length = 20)
    }
}

/**
 * Strong's [buzz] at [strength] of it, on a vibrator with amplitude control: a primitive's scale times it, a pulse's
 * amplitude times it. An effect has no strength to turn, so it is played as a pulse of its length at that amplitude.
 */
private fun scaled(buzz: Buzz, feel: Feel, strength: Float): Buzz = when (buzz) {
    is Buzz.Primitives -> Buzz.Primitives(buzz.steps.map { it.copy(scale = (it.scale * strength).coerceIn(0.05f, 1f)) })
    is Buzz.Predefined -> Buzz.Pulses(
        count = if (feel == Feel.DOUBLE) 2 else 1,
        length = if (feel == Feel.DOUBLE) 20 else pulseLength(feel),
        gap = if (feel == Feel.DOUBLE) 60 else 0,
        amplitude = amplitudeOf(strength),
    )
    is Buzz.Pulses -> {
        val full = if (buzz.amplitude == VibrationEffect.DEFAULT_AMPLITUDE) 255 else buzz.amplitude
        buzz.copy(amplitude = amplitudeOf(strength * full / 255f))
    }
    else -> buzz
}

private fun amplitudeOf(strength: Float): Int = (strength * 255).toInt().coerceIn(1, 255)

private fun pulseLength(feel: Feel): Long = when (feel) {
    Feel.TICK, Feel.POP -> 12
    Feel.LOW_TICK -> 10
    Feel.CLICK, Feel.EDGE -> 18
    else -> 22
}

/**
 * The steps under Strong on a vibrator that cannot play softer (Niko's Android 13 phone buzzes hard even on Light): each
 * step down is one rung down a ladder of plain pulses of 5, 10 and 15 ms, then the phone's tick, click and heavy click.
 * Strong's own is where [feel] stands on it; the step under that is the rung under, and the gentlest stops on the first.
 */
private fun gentler(feel: Feel, vibration: Vibration, caps: VibratorCaps): Buzz {
    val down = Vibration.STRONG.step - vibration.step
    if (feel == Feel.DOUBLE) {
        // Two pulses, 60 ms apart, of the length the step has.
        return Buzz.Pulses(count = 2, length = PULSES[(3 - down).coerceIn(0, 2)], gap = 60)
    }
    val base = when (feel) {
        Feel.LOW_TICK -> 2
        Feel.TICK, Feel.POP -> 3
        Feel.CLICK, Feel.EDGE -> 4
        else -> 5
    }
    val rung = (base - down).coerceAtLeast(0)
    if (rung < PULSES.size) return Buzz.Pulses(count = 1, length = PULSES[rung])
    val effect = when (rung) {
        3 -> VibrationEffect.EFFECT_TICK
        4 -> VibrationEffect.EFFECT_CLICK
        else -> VibrationEffect.EFFECT_HEAVY_CLICK
    }
    return if (caps.effects[effect] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO) Buzz.Predefined(effect) else Buzz.Pulses(count = 1, length = PULSES.last())
}

/** The ladder's first rungs, a plain pulse's length in ms. */
private val PULSES = longArrayOf(5, 10, 15)

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
