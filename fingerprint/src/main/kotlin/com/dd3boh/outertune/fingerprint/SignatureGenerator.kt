/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Port of the Shazam signature algorithm from SongRec by marin-m
 * (https://github.com/marin-m/SongRec, GPL-3.0), by way of the pruned `songrecfp` crate in
 * AudileTeam/Audile by Aleksey Saenko (GPL-3.0). Reimplemented in Kotlin against
 * `core/recognition/native/songrecfp/src/fingerprinting/algorithm.rs`; the algorithm is unchanged.
 */

package com.dd3boh.outertune.fingerprint

import kotlin.math.ln
import kotlin.math.max

/** The input contract. Anything else produces a signature the endpoint will not match. */
const val SIGNATURE_SAMPLE_RATE_HZ = 16000
private const val CHUNK = 128
private const val FFT_SIZE = 2048
private const val BINS = FFT_SIZE / 2 + 1          // 1025
private const val RING = 256                        // both output ring buffers, indexed by a u8

/**
 * Turns mono 16 kHz signed 16-bit audio into a Shazam signature.
 *
 * The input contract is not negotiable and was verified against the reference's own golden bytes
 * rather than from documentation: mono, signed 16-bit, exactly 16000 Hz. Samples past the last
 * whole 128 are ignored, which is what the reference's chunking does.
 *
 * Every index below that looks like it should be a subtraction with a bounds check is a wrapping
 * u8 in the reference, walking a 256 entry ring. `and 255` is that wrap, and it is why negative
 * offsets such as -46 and -49 are written as they are rather than clamped.
 */
object SignatureGenerator {

    /**
     * The Hanning window, exactly as the reference tabulates it.
     *
     * It is a symmetric Hann of length 2049 with its leading zero dropped, so it could be computed
     * from `0.5 * (1 - cos(2*PI*(n+1)/2049))` in two lines. That was tried, and rejected on
     * evidence: the reference's literals are rounded to five significant figures, computing gives
     * the correctly rounded f32 instead, and running both through the whole generator produces
     * signatures that differ in a handful of peaks. Whether Shazam would still match the computed
     * one is not something this repository can find out offline, and the implementations known to
     * be accepted use these numbers, so these are the numbers.
     *
     * Base64 rather than 2048 float literals because the JVM caps a method at 64 KB and an array
     * initialiser that long is one method. A resource file would work too, and would be one more
     * thing to survive packaging.
     */
    private val hanning: FloatArray = run {
        val bytes = java.util.Base64.getDecoder().decode(HANNING_WINDOW_2048_BASE64)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        FloatArray(FFT_SIZE) { buffer.getFloat() }
    }

    /**
     * The neighbouring bins a candidate has to beat in the spread output 49 passes back.
     *
     * Asymmetric and irregular because Shazam's is; it is not a window, it is a fixed set of
     * offsets the reference hard-codes.
     */
    private val FREQUENCY_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)

    /** And the passes it has to beat in time, as offsets into the 256 entry spread ring. */
    private val TIME_NEIGHBOURS = intArrayOf(
        -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249,
    )

    fun makeSignature(samples: ShortArray): DecodedSignature = makeSignature(samples, hanning)

    /**
     * The same, against a supplied window.
     *
     * Exists so the test can run the reference's tabulated window through the identical code path
     * and compare signatures, which is the only comparison that settles whether computing the
     * window is safe.
     */
    internal fun makeSignature(samples: ShortArray, window: FloatArray): DecodedSignature =
        State(window).run {
        var offset = 0
        while (offset + CHUNK <= samples.size) {
            doFft(samples, offset)
            doPeakSpreading()
            numSpreadFftsDone++
            // 46, not 47. The gate is the number of spread passes that must exist before there is
            // anything 46 back to look at, and one off here silently drops the first pass of every
            // signature.
            if (numSpreadFftsDone >= 46) doPeakRecognition()
            offset += CHUNK
        }
        DecodedSignature(
            sampleRateHz = SIGNATURE_SAMPLE_RATE_HZ,
            numberSamples = samples.size,
            peaksByBand = peaks.map { it.toList() },
        )
    }

    private class State(val window: FloatArray) {
        val ringOfSamples = ShortArray(FFT_SIZE)
        var ringOfSamplesIndex = 0

        val reordered = FloatArray(FFT_SIZE)
        val fft = Fft(FFT_SIZE)

        val fftOutputs = Array(RING) { FloatArray(BINS) }
        var fftOutputsIndex = 0

        val spreadFftOutputs = Array(RING) { FloatArray(BINS) }
        var spreadFftOutputsIndex = 0

        var numSpreadFftsDone = 0

        val peaks = Array(4) { mutableListOf<FrequencyPeak>() }

        fun doFft(samples: ShortArray, offset: Int) {
            samples.copyInto(
                ringOfSamples,
                destinationOffset = ringOfSamplesIndex,
                startIndex = offset,
                endIndex = offset + CHUNK,
            )
            ringOfSamplesIndex = (ringOfSamplesIndex + CHUNK) and (FFT_SIZE - 1)

            // Reorder so the newest sample lands last, and window on the way past.
            for (i in 0 until FFT_SIZE) {
                reordered[i] =
                    ringOfSamples[(i + ringOfSamplesIndex) and (FFT_SIZE - 1)].toFloat() * window[i]
            }

            val out = fftOutputs[fftOutputsIndex]
            fft.realForwardSquaredMagnitude(reordered, out)
            for (bin in 0 until BINS) {
                // The 1/131072 is the reference's fixed scaling, and the floor keeps ln() finite
                // for a silent band rather than handing it a zero.
                out[bin] = max(out[bin] / 131072f, 0.0000000001f)
            }
            fftOutputsIndex = (fftOutputsIndex + 1) and (RING - 1)
        }

        fun doPeakSpreading() {
            val source = fftOutputs[(fftOutputsIndex - 1) and (RING - 1)]
            val spread = spreadFftOutputs[spreadFftOutputsIndex]
            source.copyInto(spread)

            // Frequency-domain spread. Ascending, and it only ever writes the bin it is on, so the
            // two bins it reads ahead are still the original values; this does not cascade.
            for (position in 0..1022) {
                spread[position] = max(spread[position], max(spread[position + 1], spread[position + 2]))
            }

            // Time-domain spread, against an immutable snapshot. Hoisting a running maximum across
            // the three offsets instead would let pass -1 feed pass -3, which is a different and
            // wrong filter.
            val snapshot = spread.copyOf()
            for (former in intArrayOf(1, 3, 6)) {
                val target = spreadFftOutputs[(spreadFftOutputsIndex - former) and (RING - 1)]
                for (position in 0..1024) {
                    target[position] = max(target[position], snapshot[position])
                }
            }

            spreadFftOutputsIndex = (spreadFftOutputsIndex + 1) and (RING - 1)
        }

        fun doPeakRecognition() {
            val fftMinus46 = fftOutputs[(fftOutputsIndex - 46) and (RING - 1)]
            val fftMinus49 = spreadFftOutputs[(spreadFftOutputsIndex - 49) and (RING - 1)]

            // 1014, not 1016. The guard below reads bin + 8 and bin + 1, so the reference stops
            // where those stay inside 1024.
            for (bin in 10..1014) {
                val magnitude = fftMinus46[bin]
                // The guard reads one bin *below*. Reading it at `bin` compares a candidate against
                // its own spread self, which passes almost always and floods the signature.
                if (magnitude < 1f / 64f || magnitude < fftMinus49[bin - 1]) continue

                var maxNeighbour = 0f
                for (offset in FREQUENCY_NEIGHBOURS) {
                    maxNeighbour = max(maxNeighbour, fftMinus49[bin + offset])
                }
                if (magnitude <= maxNeighbour) continue

                var maxOther = maxNeighbour
                for (offset in TIME_NEIGHBOURS) {
                    val other = spreadFftOutputs[(spreadFftOutputsIndex + offset) and (RING - 1)]
                    maxOther = max(maxOther, other[bin - 1])
                }
                if (magnitude <= maxOther) continue

                // ln first, then floor at 1/64. Flooring the input instead diverges below x ≈
                // 1.0157 and corrupts the corrected frequency, not only the magnitude, because the
                // corrected bin is derived from these three values.
                val here = ln(magnitude).coerceAtLeast(1f / 64f) * 1477.3f + 6144f
                val before = ln(fftMinus46[bin - 1]).coerceAtLeast(1f / 64f) * 1477.3f + 6144f
                val after = ln(fftMinus46[bin + 1]).coerceAtLeast(1f / 64f) * 1477.3f + 6144f

                val variation1 = here * 2f - before - after
                // The reference asserts this is positive and panics otherwise. A music player has
                // no business dying inside a recognition, and a non-positive value would divide
                // into an infinity anyway, so the peak is dropped instead.
                if (variation1 <= 0f) continue
                val variation2 = (after - before) * 32f / variation1

                // Float to int saturates in Rust, then the sum is truncated to the low 16 bits.
                val correctedBin = ((bin * 64) + variation2.toIntSaturating()) and 0xFFFF

                val frequencyHz = correctedBin * (16000f / 2f / 1024f / 64f)
                val band = when (frequencyHz.toInt()) {
                    in 250..519 -> 0
                    in 520..1449 -> 1
                    in 1450..3499 -> 2
                    in 3500..5500 -> 3
                    else -> continue
                }

                peaks[band] += FrequencyPeak(
                    fftPassNumber = numSpreadFftsDone - 46,
                    peakMagnitude = here.toIntSaturating().coerceIn(0, 0xFFFF),
                    correctedPeakFrequencyBin = correctedBin,
                )
            }
        }
    }
}

/** Rust's `as` from float to integer saturates rather than wrapping or producing garbage. */
private fun Float.toIntSaturating(): Int = when {
    isNaN() -> 0
    this >= Int.MAX_VALUE.toFloat() -> Int.MAX_VALUE
    this <= Int.MIN_VALUE.toFloat() -> Int.MIN_VALUE
    else -> toInt()
}

/** The reference window, little-endian f32, base64. See [SignatureGenerator]. */
private const val HANNING_WINDOW_2048_BASE64 =
        "cMIdNnDCHTdQerE3lMEdOLx+djg9ebE4y4zxOG6/HTkcpkc5cHp2OTUelTkEdrE5T0PQOcSI8TnaoQo6ELodOlsOMjpjnUc6" +
        "KWdeOlhsdjp11Ic60BSVOoTvojrsZ7E6r3rAOoEu0DoIgOA66WvxOr96ATtkjgo74/ATOz2iHTvDoCc70u8xOw2MPDt1dUc7" +
        "Za9SO4I2Xjt5DGo7SjF2O6RRgTsRsoc7EzmOO1rolDtgvZs7q7qiO7TdqTsCKbE755q4O2EzwDtJ88c7yNnPO7Pn1zs1HOA7" +
        "TXfoO9P58Dvvovk7vDkBPOC0BTy7Qwo8TeYOPL6bEzx7ZBg87kAdPKwwIjzfMic8TUssPIJ0MTx9rjY8owE8PJBlQTxF2kY8" +
        "8WNMPJcCUjwDslc8aHZdPJRLYzy5NWk81zRvPLtEdTxnZXs8ns+APNTygzyfIoc8zVqKPF+bjTxt5pA83jmUPMyXlzw3AJs8" +
        "BHGePE7soTz8b6U8DfyoPJuSrDykM7A8Et2zPPuQtzxJTbs8+RG/PCbhwjzPusY83JzKPE2Hzjw5fNI8onvWPG+D2jyfk948" +
        "S67iPFvR5jzn/uo81zTvPCpz8zz5u/c8LA38PG40AD13ZgI9v50EPTjZBj3jGAk9zF0LPeemDT1B9Q89v0YSPYieFD12+RY9" +
        "o1kZPQ6/Gz2eJx49bJUgPXgIIz2qfiU9GvonPbx5Kj2b/iw9rYcvPfAUMj1ypzQ9GT03Pf7XOT0heDw9aRs/PfDDQT2ocEQ9" +
        "nyJHPbrXST0Ukkw9oFBPPV4TUj1Z21Q9h6dXPeZ3Wj13TF09OSVgPToDYz1g5GU9xcpoPVu1az0vpW49KZhxPVSPdD29i3c9" +
        "WIx6PSWRfT0STYA9qtOBPVtcgz2r54Q9jnSGPYoDiD0llYk92CiLPR++jD0EVo49AvCPPRmMkT1JKpM9ksqUPfRslj1vEZg9" +
        "AriZPa9gmz10C509UriePcNmoD3TF6I9+8qjPT2ApT2XN6c9hPCoPRGsqj0vaaw97SiuPT7qrz2nrbE9KnOzPcU6tT15BLc9" +
        "RtC4Paaduj2kbbw9Nj++PeASwD2j6ME9f8DDPe2ZxT11dcc9FVPJPc8yyz0zFs09jPjOPSLg0D25x9I9T6/UPSSc1j34iNg9" +
        "zXXaPeBn3D3zWd49RFHgPZVI4j3mP+Q9dTzmPQQ56D2TNeo9YTfsPS457j06QPA9RUfyPVFO9D1cVfY9pmH4PfBt+j14f/w9" +
        "AJH+PURRAD6nXAE+CmgCPgx2Az5vgQQ+cY8FPhKgBj60sAc+VcEIPvbRCT425Qo+d/gLPlYODT41JA4+FToPPvRPED5zaBE+" +
        "8YASPg+cEz4stxQ+StIVPgfwFj7DDRg+gCsZPj1JGj6YaRs+9IkcPu+sHT7qzx4+5fIfPt8VIT55OyI+E2EjPkyJJD7mriU+" +
        "vtkmPvcBKD7PLCk+qFcqPoCCKz73ryw+bt0tPuUKLz77OjA+c2gxPiibMj4+yzM+9P00PqkwNj7+ZTc+s5g4PgfOOT77BTs+" +
        "Tzs8PkNzPT42qz4+yeU/PlwgQT7uWkI+gZVDPrPSRD7kD0Y+Fk1HPueMSD64zEk+iQxLPllMTD7Jjk0+OdFOPqkTUD64WFE+" +
        "KJtSPjfgUz7lJ1U+9GxWPqK0Vz5Q/Fg+nUZaPuuQWz4421w+hSVePtJvXz6+vGA+qwliPjZZYz4ipmQ+rvVlPjlFZz7ElGg+" +
        "7+ZpPhk5az5Ei2w+bt1tPjgybz4Bh3A+y9txPpQwcz79h3Q+Zd91Ps42dz43jng+Puh5PkZCez5OnHw+VfZ9PvxSfz6CVoA+" +
        "1QSBPni0gT7MYoI+bxKDPhLCgz60cYQ+VyGFPkrShT48g4Y+LzSHPiHlhz5jl4g+VkiJPpj6iT4proo+a2CLPv0TjD4/xow+" +
        "0XmNPrIujj5D4o4+JJePPgZMkD7nAJE+yLWRPqlqkj7aIJM+CteTPjuNlD5sQ5U+7PqVPhyxlj6daJc+HSCYPp3XmD5tkJk+" +
        "7UeaPr0Amz6MuZs+XHKcPnwsnT5L5Z0+a5+ePopZnz6pE6A+yM2gPjeJoT5XQ6I+xf6iPjS6oz6jdaQ+EjGlPtDtpT4/qaY+" +
        "/mWnPrwiqD5636g+OZypPkdaqj4FF6s+E9WrPiGTrD4vUa0+PQ+uPkvNrj6ojK8+BkywPhMKsT5xybE+zoiyPntJsz7ZCLQ+" +
        "Nsi0PuOItT6QSbY+PQq3PurKtz6Xi7g+REy5PkEOuj7uzro+65C7PudSvD7kFL0+4Na9Pt2Yvj7ZWr8+1hzAPiLgwD5uo8E+" +
        "a2XCPrcowz4D7MM+T6/EPutzxT43N8Y+g/rGPh+/xz5rgsg+BkfJPqILyj4+0Mo+2ZTLPnVZzD4RHs0+/OPNPpeozj4zbc8+" +
        "HjPQPgn50D6lvdE+kIPSPntJ0z5nD9Q+UtXUPo2c1T54YtY+YyjXPk7u1z6Jtdg+xHzZPq9C2j7qCds+JNHbPg+X3D5KXt0+" +
        "hSXePsDs3j76s98+NXvgPr9D4T76CuI+NdLiPnCZ4z76YeQ+NSnlPr/x5T76uOY+hIHnPg5K6D5JEek+09npPl6i6j7oaus+" +
        "IzLsPq367D43w+0+wYvuPkxU7z7WHPA+YOXwPuut8T51dvI+T0DzPtkI9D5j0fQ+7Zn1Pnhi9j4CK/c+3PT3Pma9+D7whfk+" +
        "e076PlUY+z7f4Ps+aan8PkNz/T7NO/4+VwT/PjHO/z5eSwA/o68AP5AUAT/VeAE/Gt0BPwdCAj9MpgI/kQoDP9ZuAz/D0wM/" +
        "CTgEP06cBD+TAAU/gGUFP8XJBT8KLgY/T5IGP5T2Bj/ZWgc/H78HP2QjCD+phwg/7usIPzNQCT94tAk/vRgKPwN9Cj9I4Qo/" +
        "5UQLPyqpCz9vDQw/DXEMP1LVDD/vOA0/NJ0NP9IADj8XZQ4/tMgOP1IsDz+XkA8/NPQPP9FXED9vuxA/DB8RP6qCET9H5hE/" +
        "PUkSP9qsEj93EBM/bXMTPwrXEz8AOhQ/nZ0UP5MAFT+IYxU/fsYVP3QpFj9pjBY/X+8WP1RSFz9KtRc/mBcYP416GD/b3Bg/" +
        "0T8ZPx+iGT9sBBo/umYaPwjJGj9WKxs/pI0bP0rvGz+YURw/PrMcP4wVHT8ydx0/2NgdP346Hj8knB4/yv0eP3BfHz9uwB8/" +
        "FCIgPxKDID8R5CA/D0UhPw2mIT8MByI/CmgiPwjJIj9fKSM/XYojP7PqIz8KSyQ/YKskP7cLJT9mayU/vMslP2srJj/BiyY/" +
        "cOsmPx9LJz/Oqic/1QkoP4NpKD+KyCg/OSgpP0CHKT9H5ik/pkQqP62jKj8MAis/E2ErP3K/Kz/SHSw/MXwsP5DaLD9HOC0/" +
        "/5UtP170LT8VUi4/Ja8uP90MLz+Uai8/pMcvP7MkMD/DgTA/094wP+I7MT9KmDE/svQxPxpRMj+CrTI/6gkzP6plMz8SwjM/" +
        "0h00P5J5ND9S1TQ/ajA1P4KLNT9D5zU/W0I2P8ucNj/k9zY/VFI3P8WsNz81Bzg/pmE4Pxe8OD/fFTk/qG85P3HJOT86Izo/" +
        "W3w6PyTWOj9FLzs/voc7P9/gOz9YOTw/eZI8P/LqPD/EQj0/PZs9Pw7zPT/gSj4/saI+P4P6Pj+tUT8/1qg/PwAAQD8qV0A/" +
        "rK1APy4EQT+vWkE/MbFBPwwHQj+NXUI/aLNCP5oIQz90XkM/p7NDP9kIRD8LXkQ/lrJEP8gHRT9TXEU/NrBFP8AERj+jWEY/" +
        "hqxGP2kARz+kU0c/36ZHPxr6Rz9VTUg/6J9IP3zySD8PRUk/opdJP47pST95O0o/ZY1KP6neSj+UMEs/2IFLP3TSSz+4I0w/" +
        "VHRMP/DETD/kFE0/2GRNP8y0TT/ABE4/DVROPwGkTj+m8k4/8kFPP5eQTz87308/4C1QP917UD/ayVA/1xdRP9RlUT8ps1E/" +
        "fgBSPytNUj/ZmVI/huZSPzMzUz85f1M/PstTP0QXVD9JY1Q/p65UPwX6VD+7RFU/cY9VPyfaVT/dJFY/625WP/q4Vj8IA1c/" +
        "bkxXP9WVVz+U3lc/+idYP7lwWD/QuFg/jgFZP6VJWT+8kVk/K9lZP5sgWj8KaFo/0a5aP5n1Wj9gPFs/gIJbP5/IWz+/Dlw/" +
        "N1RcP6+ZXD8m31w/9iNdP8doXT+XrV0/v/FdP+c1Xj8Qel4/kL1ePxEBXz+RRF8/aodfP0PKXz90DGA/pU5gP9aQYD8H02A/" +
        "kBRhPxlWYT/7lmE/3NdhP70YYj/3WGI/MZliP2rZYj/8GGM/jlhjP3iXYz9i1mM/TRVkP49TZD/RkWQ/FNBkP64NZT9JS2U/" +
        "44hlP9bFZT/JAmY/FD9mP197Zj+qt2Y/TfNmP/EuZz+Uamc/j6VnP4vgZz/fGmg/MlVoP4aPaD8yyWg/3gJpP+I7aT/mdGk/" +
        "661pP0fmaT+jHmo/AFdqP7SOaj9pxmo/dv1qP4M0az+Qa2s/9aFrP1rYaz8XDmw/1ENsP5J5bD+nrmw/veNsPysYbT+YTG0/" +
        "BoFtP8y0bT+S6G0/sBtuP89Obj9FgW4/Y7RuPzLmbj+oGG8/d0pvP557bz/FrG8/7N1vP2sOcD/qPnA/aW9wP0GfcD8Yz3A/" +
        "SP5wP3ctcT//W3E/h4pxPw+5cT/v5nE/zxRyPwdCcj8/b3I/0JtyP2DIcj/x9HI/2iBzP8JMcz8DeHM/RKNzP4XOcz8e+XM/" +
        "uCN0P6lNdD+ad3Q/5KB0Py7KdD9383Q/GRx1PxNEdT+1bHU/CJR1PwK8dT9U43U//wl2P6kwdj9UV3Y/Vn12P1mjdj+0yHY/" +
        "D+52P2oTdz8dOHc/KVx3PzSAdz9ApHc/pMd3Pwfrdz9rDng/JzF4PztTeD9PdXg/Y5d4P9C4eD882ng/Aft4P8UbeT/iO3k/" +
        "/1t5Pxx8eT+Rm3k/Xrp5P9PZeT/593k/xhZ6P0Q0ej9pUno/5296P72Mej+TqXo/acZ6P5fiej/F/no/TBp7P9I1ez+xUHs/" +
        "kGt7P8aFez/9n3s/NLp7P8PTez9S7Xs/OgZ8P3kefD9hN3w/+U58PzhnfD/Qfnw/wJV8P7CsfD/4wnw/QNl8P4nvfD8pBX0/" +
        "yhp9P8IvfT+7RH0/DFl9P11tfT8GgX0/r5R9P1iofT9au30/s819Pw3gfT9n8n0/GQR+PyMVfj/VJn4/Nzd+P0FIfj/7V34/" +
        "Xmh+Pxh4fj8rh34/PpZ+P1Glfj+8s34/f8F+P0LPfj8F3X4/Iep+Pzz3fj+wA38/IxB/P+8bfz+7J38/3zJ/PwM+fz+ASH8/" +
        "/FJ/P3ldfz9NZ38/enB/P056fz/Ugn8/WYt/P96Tfz+7m38/mKN/P86qfz8Dsn8/kbh/Px+/fz+sxX8/kst/P9DQfz8P1n8/" +
        "Tdt/P+Pffz/S438/wOd/P6/rfz/27n8/PfJ/P9z0fz97938/cvl/P2r7fz+5/H8/Cf5/P1j/fz9Y/38/AACAPwAAgD9Y/38/" +
        "WP9/Pwn+fz+5/H8/avt/P3L5fz97938/3PR/Pz3yfz/27n8/r+t/P8Dnfz/S438/499/P03bfz8P1n8/0NB/P5LLfz+sxX8/" +
        "H79/P5G4fz8Dsn8/zqp/P5ijfz+7m38/3pN/P1mLfz/Ugn8/Tnp/P3pwfz9NZ38/eV1/P/xSfz+ASH8/Az5/P98yfz+7J38/" +
        "7xt/PyMQfz+wA38/PPd+PyHqfj8F3X4/Qs9+P3/Bfj+8s34/UaV+Pz6Wfj8rh34/GHh+P15ofj/7V34/QUh+Pzc3fj/VJn4/" +
        "IxV+PxkEfj9n8n0/DeB9P7PNfT9au30/WKh9P6+UfT8GgX0/XW19PwxZfT+7RH0/wi99P8oafT8pBX0/ie98P0DZfD/4wnw/" +
        "sKx8P8CVfD/Qfnw/OGd8P/lOfD9hN3w/eR58PzoGfD9S7Xs/w9N7PzS6ez/9n3s/xoV7P5Brez+xUHs/0jV7P0waez/F/no/" +
        "l+J6P2nGej+TqXo/vYx6P+dvej9pUno/RDR6P8YWej/593k/09l5P166eT+Rm3k/HHx5P/9beT/iO3k/xRt5PwH7eD882ng/" +
        "0Lh4P2OXeD9PdXg/O1N4PycxeD9rDng/B+t3P6THdz9ApHc/NIB3Pylcdz8dOHc/ahN3Pw/udj+0yHY/WaN2P1Z9dj9UV3Y/" +
        "qTB2P/8Jdj9U43U/Arx1PwiUdT+1bHU/E0R1PxkcdT9383Q/Lsp0P+SgdD+ad3Q/qU10P7gjdD8e+XM/hc5zP0Sjcz8DeHM/" +
        "wkxzP9ogcz/x9HI/YMhyP9Cbcj8/b3I/B0JyP88Ucj/v5nE/D7lxP4eKcT//W3E/dy1xP0j+cD8Yz3A/QZ9wP2lvcD/qPnA/" +
        "aw5wP+zdbz/FrG8/nntvP3dKbz+oGG8/MuZuP2O0bj9FgW4/z05uP7Abbj+S6G0/zLRtPwaBbT+YTG0/KxhtP73jbD+nrmw/" +
        "knlsP9RDbD8XDmw/WthrP/Whaz+Qa2s/gzRrP3b9aj9pxmo/tI5qPwBXaj+jHmo/R+ZpP+utaT/mdGk/4jtpP94CaT8yyWg/" +
        "ho9oPzJVaD/fGmg/i+BnP4+lZz+Uamc/8S5nP03zZj+qt2Y/X3tmPxQ/Zj/JAmY/1sVlP+OIZT9JS2U/rg1lPxTQZD/RkWQ/" +
        "j1NkP00VZD9i1mM/eJdjP45YYz/8GGM/atliPzGZYj/3WGI/vRhiP9zXYT/7lmE/GVZhP5AUYT8H02A/1pBgP6VOYD90DGA/" +
        "Q8pfP2qHXz+RRF8/EQFfP5C9Xj8Qel4/5zVeP7/xXT+XrV0/x2hdP/YjXT8m31w/r5lcPzdUXD+/Dlw/n8hbP4CCWz9gPFs/" +
        "mfVaP9GuWj8KaFo/myBaPyvZWT+8kVk/pUlZP44BWT/QuFg/uXBYP/onWD+U3lc/1ZVXP25MVz8IA1c/+rhWP+tuVj/dJFY/" +
        "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" +
        "NlljPqsJYj6+vGA+0m9fPoUlXj4421w+65BbPp1GWj5Q/Fg+orRXPvRsVj7lJ1U+N+BTPiibUj64WFE+qRNQPjnRTj7Jjk0+" +
        "WUxMPokMSz64zEk+54xIPhZNRz7kD0Y+s9JEPoGVQz7uWkI+XCBBPsnlPz42qz4+Q3M9Pk87PD77BTs+B845PrOYOD7+ZTc+" +
        "qTA2PvT9ND4+yzM+KJsyPnNoMT77OjA+5QovPm7dLT73ryw+gIIrPqhXKj7PLCk+9wEoPr7ZJj7mriU+TIkkPhNhIz55OyI+" +
        "3xUhPuXyHz7qzx4+76wdPvSJHD6YaRs+PUkaPoArGT7DDRg+B/AWPkrSFT4stxQ+D5wTPvGAEj5zaBE+9E8QPhU6Dz41JA4+" +
        "Vg4NPnf4Cz425Qo+9tEJPlXBCD60sAc+EqAGPnGPBT5vgQQ+DHYDPgpoAj6nXAE+RFEAPgCR/j14f/w98G36PaZh+D1cVfY9" +
        "UU70PUVH8j06QPA9LjnuPWE37D2TNeo9BDnoPXU85j3mP+Q9lUjiPURR4D3zWd494GfcPc112j34iNg9JJzWPU+v1D25x9I9" +
        "IuDQPYz4zj0zFs09zzLLPRVTyT11dcc97ZnFPX/Awz2j6ME94BLAPTY/vj2kbbw9pp26PUbQuD15BLc9xTq1PSpzsz2nrbE9" +
        "PuqvPe0orj0vaaw9EayqPYTwqD2XN6c9PYClPfvKoz3TF6I9w2agPVK4nj10C509r2CbPQK4mT1vEZg99GyWPZLKlD1JKpM9" +
        "GYyRPQLwjz0EVo49H76MPdgoiz0llYk9igOIPY50hj2r54Q9W1yDParTgT0STYA9JZF9PViMej29i3c9VI90PSmYcT0vpW49" +
        "W7VrPcXKaD1g5GU9OgNjPTklYD13TF095ndaPYenVz1Z21Q9XhNSPaBQTz0Ukkw9utdJPZ8iRz2ocEQ98MNBPWkbPz0heDw9" +
        "/tc5PRk9Nz1ypzQ98BQyPa2HLz2b/iw9vHkqPRr6Jz2qfiU9eAgjPWyVID2eJx49Dr8bPaNZGT12+RY9iJ4UPb9GEj1B9Q89" +
        "56YNPcxdCz3jGAk9ONkGPb+dBD13ZgI9bjQAPSwN/Dz5u/c8KnPzPNc07zzn/uo8W9HmPEuu4jyfk948b4PaPKJ71jw5fNI8" +
        "TYfOPNycyjzPusY8JuHCPPkRvzxJTbs8+5C3PBLdszykM7A8m5KsPA38qDz8b6U8TuyhPARxnjw3AJs8zJeXPN45lDxt5pA8" +
        "X5uNPM1aijyfIoc81PKDPJ7PgDxnZXs8u0R1PNc0bzy5NWk8lEtjPGh2XTwDslc8lwJSPPFjTDxF2kY8kGVBPKMBPDx9rjY8" +
        "gnQxPE1LLDzfMic8rDAiPO5AHTx7ZBg8vpsTPE3mDjy7Qwo84LQFPLw5ATzvovk70/nwO0136Ds1HOA7s+fXO8jZzztJ88c7" +
        "YTPAO+eauDsCKbE7tN2pO6u6ojtgvZs7WuiUOxM5jjsRsoc7pFGBO0oxdjt5DGo7gjZeO2WvUjt1dUc7DYw8O9LvMTvDoCc7" +
        "PaIdO+PwEztkjgo7v3oBO+lr8ToIgOA6gS7QOq96wDrsZ7E6hO+iOtAUlTp11Ic6WGx2OilnXjpjnUc6Ww4yOhC6HTraoQo6" +
        "xIjxOU9D0DkEdrE5NR6VOXB6djkcpkc5br8dOcuM8Tg9ebE4vH52OJTBHThQerE3cMIdN3DCHTY="
