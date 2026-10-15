package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AutoEq's list of models and a model's ten band curve, read as they are written. The lines and
 * the Sennheiser HD 800's file are copied from github.com/jaakkopasanen/AutoEq on 15 Oct 2026.
 */
class AutoEqTest {

    private val index = """
        # Index
        This is a list of all equalization profiles. Target is in parentheses if there are results with multiple targets
        from the same source.

        - [1Custom SA02](./crinacle/711%20in-ear/1Custom%20SA02) by crinacle on 711
        - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
        - [1MORE ComfoBuds Mini](./DHRME/in-ear/1MORE%20ComfoBuds%20Mini) by DHRME
        - [Sennheiser HD 800](./oratory1990/over-ear/Sennheiser%20HD%20800) by oratory1990
    """.trimIndent()

    private val hd800 = """
        Preamp: -7.5 dB
        Filter 1: ON PK Fc 31 Hz Gain 6.6 dB Q 1.41
        Filter 2: ON PK Fc 62 Hz Gain 4.6 dB Q 1.41
        Filter 3: ON PK Fc 125 Hz Gain -0.4 dB Q 1.41
        Filter 4: ON PK Fc 250 Hz Gain -1.9 dB Q 1.41
        Filter 5: ON PK Fc 500 Hz Gain -0.1 dB Q 1.41
        Filter 6: ON PK Fc 1000 Hz Gain -0.1 dB Q 1.41
        Filter 7: ON PK Fc 2000 Hz Gain 3.9 dB Q 1.41
        Filter 8: ON PK Fc 4000 Hz Gain -1.4 dB Q 1.41
        Filter 9: ON PK Fc 8000 Hz Gain -2.7 dB Q 1.41
        Filter 10: ON PK Fc 16000 Hz Gain -7.5 dB Q 1.41
    """.trimIndent()

    @Test
    fun theIndexGivesEveryModelWithItsFolderAndWhoMeasuredIt() {
        val models = AutoEq.parseIndex(index)

        assertEquals(4, models.size)
        assertEquals(AutoEq.Model("1Custom SA02", "crinacle/711%20in-ear/1Custom%20SA02", "crinacle", "711"), models[0])
        assertEquals(
            AutoEq.Model("1MORE Aero (ANC Off)", "HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)", "HypetheSonics", "GRAS RA0045"),
            models[1]
        )
        assertEquals(AutoEq.Model("Sennheiser HD 800", "oratory1990/over-ear/Sennheiser%20HD%20800", "oratory1990", null), models[3])
    }

    @Test
    fun aModelsCurveIsItsFolderNameAndFixedBandEq() {
        val (_, aero, _, sennheiser) = AutoEq.parseIndex(index)

        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/over-ear/Sennheiser%20HD%20800/Sennheiser%20HD%20800%20FixedBandEQ.txt",
            AutoEq.profileUrl(sennheiser)
        )
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)/1MORE%20Aero%20(ANC%20Off)%20FixedBandEQ.txt",
            AutoEq.profileUrl(aero)
        )
    }

    @Test
    fun theTenFiltersAreTheEqualizersTenBandsLeftToRight() {
        assertEquals(listOf(6.6f, 4.6f, -0.4f, -1.9f, -0.1f, -0.1f, 3.9f, -1.4f, -2.7f, -7.5f), AutoEq.parseFixedBand(hd800))
    }

    @Test
    fun aGainBeyondTheSlidersIsKeptAtTheirEnd() {
        val loud = hd800.replace("Gain 6.6 dB", "Gain 14.2 dB").replace("Gain -7.5 dB Q", "Gain -15 dB Q")
        val gains = AutoEq.parseFixedBand(loud)!!

        assertEquals(EqualizerAudioProcessor.MAX_DB, gains.first())
        assertEquals(-EqualizerAudioProcessor.MAX_DB, gains.last())
    }

    @Test
    fun aCurveOnOtherBandsIsNotTaken() {
        assertNull("nine filters", AutoEq.parseFixedBand(hd800.lines().dropLast(1).joinToString("\n")))
        assertNull("another frequency", AutoEq.parseFixedBand(hd800.replace("Fc 2000 Hz", "Fc 2500 Hz")))
        assertNull("another Q", AutoEq.parseFixedBand(hd800.replace("Fc 500 Hz Gain -0.1 dB Q 1.41", "Fc 500 Hz Gain -0.1 dB Q 0.7")))
        assertNull("a parametric file", AutoEq.parseFixedBand("Preamp: -6.4 dB\nFilter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70"))
    }

    @Test
    fun thePickIsShownWhileTheSlidersAreWhereItPutThem() {
        val model = AutoEq.parseIndex(index)[3]
        val curve = EqualizerPreset.format(AutoEq.parseFixedBand(hd800)!!)
        val kept = AutoEq.keep(model, curve)

        assertEquals("Sennheiser HD 800" to "oratory1990", AutoEq.chosen(kept, curve))
        assertNull("a band moved since", AutoEq.chosen(kept, curve.replaceFirst("6.6", "7.0")))
        assertNull("nothing picked", AutoEq.chosen("", curve))
    }

    @Test
    fun searchWantsEveryWordInTheName() {
        val models = AutoEq.parseIndex(index)

        assertEquals(listOf("1MORE Aero (ANC Off)", "1MORE ComfoBuds Mini"), AutoEq.search(models, "1more").map { it.name })
        assertEquals(listOf("1MORE ComfoBuds Mini"), AutoEq.search(models, "mini 1more").map { it.name })
        assertEquals(listOf("Sennheiser HD 800"), AutoEq.search(models, "HD 800").map { it.name })
        assertEquals(emptyList<AutoEq.Model>(), AutoEq.search(models, "  "))
    }
}
