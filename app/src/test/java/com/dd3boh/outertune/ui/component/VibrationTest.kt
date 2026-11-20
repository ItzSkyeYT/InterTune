/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.dd3boh.outertune.ui.component.Buzz.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Vibration setting (Unreleased.HAPTICS_FEEL; his ask of 20 Nov 2026 on his S25U, "the haptics aren't really
 * noticeable... how custom can we make them?"): Off, Light (today's) or Strong (the app's own, a feel per action).
 * What Strong plays on a phone is a pure choice ([buzzFor]) and is tested as such; where the feels are wired is read
 * from the source, as MoreHapticsTest does.
 */
class VibrationTest {

    private val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    private val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    private val lowTick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    private val yes = Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    private val no = Vibrator.VIBRATION_EFFECT_SUPPORT_NO

    /** His S25U as dumpsys vibrator_manager read it on 20 Nov 2026: eight primitives, four effects, amplitude control. */
    private val s25u = VibratorCaps(
        sdk = 36,
        hasVibrator = true,
        primitives = setOf(
            click, tick, lowTick, VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_SPIN,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
        ),
        effects = mapOf(
            VibrationEffect.EFFECT_CLICK to yes, VibrationEffect.EFFECT_DOUBLE_CLICK to yes,
            VibrationEffect.EFFECT_TICK to yes, VibrationEffect.EFFECT_HEAVY_CLICK to yes,
        ),
        amplitudeControl = true,
    )

    /** A phone with the predefined effects and no primitives: what many phones of Android 11 and 12 have. */
    private val effectsOnly = s25u.copy(primitives = emptySet())

    private fun strong(feel: Feel, caps: VibratorCaps = s25u) = buzzFor(feel, Vibration.STRONG, caps)

    private fun steps(vararg steps: Step) = Buzz.Primitives(steps.toList())

    // ---- the three choices ----

    @Test
    fun `Off plays nothing and Light plays today's, whatever the phone and the action`() {
        for (caps in listOf(s25u, effectsOnly, s25u.copy(sdk = 26), s25u.copy(hasVibrator = false))) {
            for (feel in Feel.entries) {
                assertEquals(Buzz.None, buzzFor(feel, Vibration.OFF, caps))
                assertEquals(Buzz.Light, buzzFor(feel, Vibration.LIGHT, caps))
            }
        }
    }

    @Test
    fun `Strong is the trial's default, his S25U finding Light too faint`() {
        assertEquals(Vibration.STRONG, Vibration.DEFAULT)
        assertEquals(listOf(Vibration.OFF, Vibration.LIGHT, Vibration.STRONG), Vibration.entries)
    }

    // ---- Strong on his S25U: the primitives ----

    @Test
    fun `on his S25U Strong plays a primitive per action, at the brief's strengths`() {
        // Play and pause, a long press, the seek bar let go: a firm click.
        assertEquals(steps(Step(click, 1f)), strong(Feel.FIRM))
        // The volume landing on 100%: a click.
        assertEquals(steps(Step(click, 1f)), strong(Feel.CLICK))
        // Skip, shuffle, repeat, every tap: a tick.
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK))
        // The seek bar's ticks while it is dragged: low ones.
        assertEquals(steps(Step(lowTick, 0.35f)), strong(Feel.LOW_TICK))
        // A like: two clicks 60 ms apart, the second the stronger, as Essentials' double has them.
        assertEquals(steps(Step(click, 0.7f), Step(click, 1f, delay = 60)), strong(Feel.DOUBLE))
    }

    @Test
    fun `on Android 11 there is no low tick, and the seek bar ticks with a softer tick`() {
        // PRIMITIVE_LOW_TICK is API 31: an Android 11 phone never says it plays one.
        val android11 = s25u.copy(sdk = 30, primitives = setOf(click, tick))
        assertEquals(steps(Step(tick, 0.3f)), strong(Feel.LOW_TICK, android11))
        assertEquals(steps(Step(tick, 1f)), strong(Feel.TICK, android11))
        assertEquals(steps(Step(click, 1f)), strong(Feel.FIRM, android11))
    }

    // ---- Strong without primitives: the predefined effects, then plain pulses ----

    @Test
    fun `without primitives Strong plays the phone's predefined effects`() {
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), strong(Feel.FIRM, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_CLICK), strong(Feel.CLICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.TICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.LOW_TICK, effectsOnly))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK), strong(Feel.DOUBLE, effectsOnly))
    }

    @Test
    fun `a phone that cannot say which effects it plays gets them all the same, Android's own stand-ins behind them`() {
        // Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN: the vibrator does not list its effects. createPredefined then
        // plays Android's fallback pattern where the hardware has none, so the effect is still the best guess.
        val unknown = effectsOnly.copy(effects = emptyMap())
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK), strong(Feel.FIRM, unknown))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK), strong(Feel.DOUBLE, unknown))
        val saysUnknown = unknown.copy(effects = mapOf(VibrationEffect.EFFECT_TICK to Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN))
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_TICK), strong(Feel.TICK, saysUnknown))
    }

    @Test
    fun `a firm click the phone has no heavy click for is its plain click, and a double it has none for two pulses`() {
        // As the emulator's vibrator: CLICK and TICK, no DOUBLE_CLICK or HEAVY_CLICK.
        val clickAndTick = effectsOnly.copy(
            effects = mapOf(
                VibrationEffect.EFFECT_CLICK to yes, VibrationEffect.EFFECT_TICK to yes,
                VibrationEffect.EFFECT_DOUBLE_CLICK to no, VibrationEffect.EFFECT_HEAVY_CLICK to no,
            ),
        )
        assertEquals(Buzz.Predefined(VibrationEffect.EFFECT_CLICK), strong(Feel.FIRM, clickAndTick))
        assertEquals(Buzz.Pulses(count = 2, length = 20, gap = 60), strong(Feel.DOUBLE, clickAndTick))
    }

    @Test
    fun `a phone with none of it gets plain pulses of 20 ms, a soft short one for the seek bar`() {
        val none = effectsOnly.copy(effects = Feel.entries.flatMap { predefinedFor(it) }.associateWith { no })
        for (feel in listOf(Feel.FIRM, Feel.CLICK, Feel.TICK)) {
            assertEquals(feel.name, Buzz.Pulses(count = 1, length = 20), strong(feel, none))
        }
        assertEquals(Buzz.Pulses(count = 2, length = 20, gap = 60), strong(Feel.DOUBLE, none))
        // Soft where the vibrator can play softly, as Essentials' subtle stand-in (10 ms at 80); short where it cannot.
        assertEquals(Buzz.Pulses(count = 1, length = 10, amplitude = 50), strong(Feel.LOW_TICK, none))
        assertEquals(Buzz.Pulses(count = 1, length = 10), strong(Feel.LOW_TICK, none.copy(amplitudeControl = false)))
    }

    @Test
    fun `the pulses are a waveform that starts at once, on and off by turns`() {
        val double = Buzz.Pulses(count = 2, length = 20, gap = 60)
        assertEquals(listOf(0L, 20L, 60L, 20L), double.timings().toList())
        assertEquals(listOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE), double.amplitudes().toList())
        val soft = Buzz.Pulses(count = 1, length = 10, amplitude = 50)
        assertEquals(listOf(0L, 10L), soft.timings().toList())
        assertEquals(listOf(0, 80), soft.amplitudes().toList())
    }

    @Test
    fun `below Android 11 and without a vibrator Strong is Light's way`() {
        // Below API 30 there are no primitives and no answer about effects: the brief keeps today's haptics there.
        for (sdk in listOf(24, 26, 29)) {
            for (feel in Feel.entries) assertEquals("$feel on $sdk", Buzz.Light, strong(feel, s25u.copy(sdk = sdk)))
        }
        for (feel in Feel.entries) assertEquals(Buzz.Light, strong(feel, s25u.copy(hasVibrator = false)))
    }

    @Test
    fun `Strong never asks for a primitive the phone lacks, nor an effect it says it cannot play`() {
        val primitives = listOf(click, tick, lowTick)
        val answers = listOf(yes, no, null)
        val effects = listOf(
            VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_DOUBLE_CLICK, VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_HEAVY_CLICK,
        )
        var phones = 0
        for (mask in 0 until (1 shl primitives.size)) {
            val has = primitives.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (a in answers) for (b in answers) for (c in answers) for (d in answers) {
                val said = effects.zip(listOf(a, b, c, d)).filter { it.second != null }.associate { it.first to it.second!! }
                for (sdk in listOf(30, 31, 36)) {
                    // An Android 11 phone never lists the low tick, which came with Android 12.
                    val caps = VibratorCaps(sdk, true, if (sdk < 31) has - lowTick else has, said, amplitudeControl = mask % 2 == 0)
                    phones++
                    for (feel in Feel.entries) {
                        when (val buzz = strong(feel, caps)) {
                            is Buzz.Primitives -> assertTrue("$feel on $caps: $buzz", buzz.steps.all { it.primitive in caps.primitives })
                            is Buzz.Predefined -> assertTrue("$feel on $caps: $buzz", caps.effects[buzz.effect] != no)
                            is Buzz.Pulses -> assertTrue("$feel on $caps: $buzz", predefinedFor(feel).all { caps.effects[it] == no })
                            else -> error("$feel on $caps: Strong played $buzz on Android 11 or later with a vibrator")
                        }
                    }
                }
            }
        }
        assertEquals(8 * 81 * 3, phones)
    }

    // ---- what the app's other haptics feel like under Strong ----

    @Test
    fun `Compose's haptics each have a feel under Strong, the way the player's buttons use them`() {
        // Play and pause and the seek bar let go ask Confirm; a song row's long press LongPress.
        assertEquals(Feel.FIRM, feelOf(HapticFeedbackType.Confirm))
        assertEquals(Feel.FIRM, feelOf(HapticFeedbackType.LongPress))
        // Skip, shuffle and repeat ask SegmentFrequentTick; the taps ContextClick.
        for (type in listOf(
            HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.ContextClick, HapticFeedbackType.VirtualKey,
            HapticFeedbackType.KeyboardTap, HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff,
            HapticFeedbackType.GestureThresholdActivate,
        )) assertEquals(type.toString(), Feel.TICK, feelOf(type))
        // Landing somewhere: the lyrics' timing back on zero, a swipe past its line.
        assertEquals(Feel.CLICK, feelOf(HapticFeedbackType.SegmentTick))
        assertEquals(Feel.CLICK, feelOf(HapticFeedbackType.GestureEnd))
        // A row's swipe past its second line, a refusal: two clicks, as Android's own reject is a double buzz.
        assertEquals(Feel.DOUBLE, feelOf(HapticFeedbackType.Reject))
        // A handle dragged along: low ticks, as the seek bar's.
        assertEquals(Feel.LOW_TICK, feelOf(HapticFeedbackType.TextHandleMove))
    }

    // ---- where the feels are wired ----

    private val sources = File("src/main/java/com/dd3boh/outertune")

    /** The source of [path] with its comments taken out. */
    private fun source(path: String) = File(sources, path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(^|\s)//.*$""", RegexOption.MULTILINE), "$1")

    private val haptics get() = source("ui/component/Haptics.kt")

    @Test
    fun `held back to the debug app, the Preview and the Tester app, and without it every haptic is today's`() {
        assertTrue("val HAPTICS_FEEL = BuildConfig.PREVIEW_FEATURES" in source("constants/Unreleased.kt"))
        // The setting is Light where the flag is off, and Light runs today's call unchanged.
        val vibration = haptics.substringAfter("fun vibration(): Vibration {").substringBefore("\n    }")
        assertTrue(vibration, "if (!Unreleased.HAPTICS_FEEL) return Vibration.LIGHT" in vibration)
        assertTrue("context.dataStore[VibrationKey].toEnum(Vibration.DEFAULT)" in vibration)
        val play = haptics.substringAfter("inline fun play(").substringBefore("\n    }")
        assertTrue(play, "Vibration.OFF -> Unit" in play && "Vibration.LIGHT -> light()" in play)
        assertTrue(play, "Vibration.STRONG -> if (!strong(feel)) light()" in play)
        // And the window keeps Compose's own haptic feedback where the flag is off.
        assertTrue("if (Unreleased.HAPTICS_FEEL) FeltHaptics(platform) else platform" in haptics)
    }

    @Test
    fun `the window's haptics carry the setting, so Compose's own haptics follow it there`() {
        val main = source("MainActivity.kt")
        assertTrue("val haptic = rememberFeltHaptics()" in main)
        assertTrue("LocalHapticFeedback provides haptic," in main)
        // Each type as the setting says, today's call kept for Light.
        assertTrue("Haptics.play(feelOf(hapticFeedbackType)) { platform.performHapticFeedback(hapticFeedbackType) }" in haptics)
        assertTrue("Haptics.start(this)" in source("App.kt"))
    }

    @Test
    fun `Strong vibrates as touch feedback, so the phone's own switches and strength still apply, and only with leave to`() {
        assertTrue("VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)" in haptics)
        // Android 11 and 12 take audio attributes, and Android turns sonification into touch.
        assertTrue(".setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)" in haptics)
        assertTrue("Manifest.permission.VIBRATE" in haptics)
        // Never the old vibrate(long) or a waveform that repeats: every buzz is one-shot.
        assertFalse(Regex("""vibrate\(\s*\d""").containsMatchIn(haptics))
        assertTrue("createWaveform(buzz.timings(), buzz.amplitudes(), -1)" in haptics)
    }

    @Test
    fun `a tap, the seek bar and the volume landing ask the setting themselves, today's haptic inside`() {
        // They also play inside the menus and dialogs, whose windows have Compose's own haptic feedback.
        val helper = source("ui/component/MoreHaptics.kt")
        assertTrue("if (Unreleased.HAPTICS_MORE) Haptics.play(strong) { performHapticFeedback(HapticFeedbackType.ContextClick) }" in helper)
        assertTrue("fun HapticFeedback.tapTick(strong: Feel = Feel.TICK)" in helper)
        val segment = helper.substringAfter("fun HapticFeedback.segmentTick(").substringBefore("\n}")
        assertTrue(segment, "Haptics.play(if (frequent) Feel.LOW_TICK else Feel.CLICK) {" in segment)
        assertTrue(segment, "TickWay.CLOCK -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)" in segment)
    }

    @Test
    fun `a like plays two clicks and taking it back the tap's tick, in the player and in a song's menu`() {
        val player = source("ui/player/Player.kt")
        // Classic's circle and the connected row's segment, in both places the row is drawn.
        assertEquals(3, Regex("""tap = if \((currentSong\?\.song\?\.liked == true|liked)\) Feel\.TICK else Feel\.DOUBLE""").findAll(player).count())
        for (header in listOf("private fun PlayerCircleButton(", "private fun PlayerActionSegment(")) {
            val button = player.substringAfter(header).substringBefore("\n}")
            assertTrue("$header takes no feel", "tap: Feel = Feel.TICK" in button)
            assertTrue("$header does not tick with it", "haptic.tapTick(tap)" in button)
        }
        assertTrue("haptic.tapTick(if (song.song.liked) Feel.TICK else Feel.DOUBLE)" in source("ui/menu/SongMenu.kt"))
        assertTrue("haptic.tapTick(if (librarySong?.song?.liked == true) Feel.TICK else Feel.DOUBLE)" in source("ui/menu/YouTubeSongMenu.kt"))
    }

    @Test
    fun `the row is under Look and feel's Behavior, after the swipes, and a pick plays one buzz of it`() {
        val look = source("ui/screens/settings/LookAndFeelSettings.kt")
        assertTrue("SwipeGesturesFrag()\n            if (Unreleased.HAPTICS_FEEL) VibrationFrag()" in look)
        val row = source("ui/screens/settings/fragments/InterfaceFrag.kt").substringAfter("fun ColumnScope.VibrationFrag() {").substringBefore("\n}")
        assertTrue(row, "rememberEnumPreference(VibrationKey, defaultValue = Vibration.DEFAULT)" in row)
        assertTrue(row, "Modifier.settingRow(R.string.vibration)" in row)
        assertTrue(row, "Haptics.sample(picked, platform)" in row)
        // Light's buzz is today's own, never the window's, which still holds the old choice as it is picked.
        assertTrue(row, "val platform = (haptic as? FeltHaptics)?.platform ?: haptic" in row)
    }

    @Test
    fun `its texts are short and held from translators until his pick, French beside them`() {
        val english = File("src/main/res/values/strings-ot.xml").readText()
        val french = File("src/main/res/values-fr/strings-ot.xml").readText()
        fun line(xml: String, name: String) = xml.lines().singleOrNull { "name=\"$name\"" in it } ?: error("$name is missing")
        for (name in listOf("vibration", "vibration_off", "vibration_light", "vibration_strong")) {
            for (xml in listOf(english, french)) {
                val entry = line(xml, name)
                assertTrue("$name is open to translators: $entry", "translatable=\"false\"" in entry)
                val text = entry.substringAfter(">").substringBefore("</string>")
                assertTrue("$name: $text", text.isNotBlank() && text.length <= 12 && '—' !in text)
            }
        }
        assertEquals("Strong", line(english, "vibration_strong").substringAfter(">").substringBefore("<"))
        assertEquals("Forte", line(french, "vibration_strong").substringAfter(">").substringBefore("<"))
    }

    @Test
    fun `a release asks to vibrate exactly when it has the setting`() {
        // As the camera before rooms shipped (RoomsCameraReleaseTest): the debug app, the Preview and the Tester app
        // ask for it, a release takes it out while the flag is held back, so 0.13 asks for nothing new.
        val main = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("""<uses-permission android:name="android\.permission\.VIBRATE"\s*/>""").containsMatchIn(main))
        val held = "val HAPTICS_FEEL = BuildConfig.PREVIEW_FEATURES" in source("constants/Unreleased.kt")
        val release = File("src/release/AndroidManifest.xml").readText()
        val stripped = Regex("""android\.permission\.VIBRATE"\s+tools:node="remove"""").containsMatchIn(release)
        assertTrue("the setting is held back but a release still asks to vibrate", !held || stripped)
        assertTrue("the setting ships but its manifest takes the vibrator away", held || !stripped)
    }
}
