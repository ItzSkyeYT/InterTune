/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Confetti, twice in the whole story: a burst for number one, from both lower corners and from
 * behind its cover, and a short shower on the thank-you page. Paper bits whose path is worked out
 * from the time (a throw slowed by the air, falling, swaying), so any frame can be drawn and a held
 * story holds them in the air. Never with Remove animations on (RecapStage does not start them).
 */
internal class RecapConfetti {
    private class Bit(
        val t0: Long, val x0: Float, val y0: Float, val vx: Float, val vy: Float, val drag: Float, val fall: Float,
        val sway: Float, val swayRate: Float, val swayPhase: Float, val turn0: Float, val spin: Float, val flip: Float,
        val colour: Color, val kind: Int, val size: Float,
    )

    private var bits: List<Bit> = emptyList()
        set(value) {
            field = value
            active = value.isNotEmpty()
        }
    private val tri = Path()
    private val curl = Path()

    /** Whether any bit is in the air: what the drawing reads, so that nothing is drawn for nothing. */
    var active by mutableStateOf(false)
        private set

    fun clear() {
        bits = emptyList()
    }

    /** Number one's burst: two guns at the foot of the screen and one behind the cover at [hero]. */
    fun burst(now: Long, hero: Offset, size: Size, unit: Float) {
        val r = Random(90)
        val guns = listOf(
            floatArrayOf(30f / 414f * size.width, 700f / 736f * size.height, -62f, 1f),
            floatArrayOf(384f / 414f * size.width, 700f / 736f * size.height, -118f, 1f),
            floatArrayOf(hero.x, hero.y, -90f, 2.2f),
        )
        bits = buildList {
            guns.forEachIndexed { g, (x0, y0, dir, spread) ->
                repeat(if (g == 2) 70 else 60) {
                    val ang = (dir + (r.nextFloat() - 0.5f) * 46f * spread) * PI.toFloat() / 180f
                    val speed = ((if (g == 2) 520f else 900f) + r.nextFloat() * 700f) * unit
                    add(
                        Bit(
                            now + (r.nextFloat() * 90).toLong(), x0, y0, cos(ang) * speed, sin(ang) * speed, 2.1f + r.nextFloat() * 1.2f,
                            (520f + r.nextFloat() * 200f) * unit, (10f + r.nextFloat() * 26f) * unit, 2f + r.nextFloat() * 3f, r.nextFloat() * 6f,
                            r.nextFloat() * 6f, (r.nextFloat() - 0.5f) * 14f, 5f + r.nextFloat() * 9f, COLOURS[r.nextInt(COLOURS.size)],
                            r.nextInt(4), (5f + r.nextFloat() * 5f) * unit,
                        )
                    )
                }
            }
        }
    }

    /** The thank-you shower: let go over about two seconds and over by about eight; then the page rests. */
    fun rain(now: Long, size: Size, unit: Float) {
        val r = Random(10)
        bits = List(64) {
            Bit(
                now + (r.nextFloat() * 2200).toLong(), r.nextFloat() * size.width, -20f * unit, (r.nextFloat() - 0.5f) * 40f * unit,
                (50f + r.nextFloat() * 70f) * unit, 1.4f, (185f + r.nextFloat() * 85f) * unit, (14f + r.nextFloat() * 22f) * unit,
                1.2f + r.nextFloat() * 1.6f, r.nextFloat() * 6f, r.nextFloat() * 6f, (r.nextFloat() - 0.5f) * 5f, 2f + r.nextFloat() * 4f,
                COLOURS[r.nextInt(COLOURS.size)], r.nextInt(4), (5f + r.nextFloat() * 4f) * unit,
            )
        }
    }

    /** Forgets the bits once every one has left the screen. */
    fun cull(now: Long, size: Size) {
        if (bits.isEmpty()) return
        val floor = size.height + 24f * (size.width / 414f)
        val flying = bits.any { b ->
            val s = (now - b.t0) / 1000f
            s < 0f || position(b, s).y <= floor
        }
        if (!flying) bits = emptyList()
    }

    private fun position(b: Bit, s: Float): Offset {
        val e = exp(-b.drag * s)
        val x = b.x0 + b.vx * (1 - e) / b.drag + b.sway * sin(b.swayRate * s + b.swayPhase) * min(1f, s)
        val y = b.y0 + (b.fall / b.drag) * s + (b.vy - b.fall / b.drag) * (1 - e) / b.drag
        return Offset(x, y)
    }

    fun DrawScope.draw(now: Long) {
        for (b in bits) {
            val s = (now - b.t0) / 1000f
            if (s < 0f) continue
            val at = position(b, s)
            if (at.y > size.height + b.size * 4 || at.x < -b.size * 6 || at.x > size.width + b.size * 6) continue
            translate(at.x, at.y) {
                rotateRad(b.turn0 + b.spin * s, Offset.Zero) {
                    scale(1f, cos(b.flip * s), Offset.Zero) {
                        when (b.kind) {
                            0 -> drawRect(b.colour, Offset(-b.size, -b.size * 0.45f), Size(b.size * 2, b.size * 0.9f))
                            1 -> drawCircle(b.colour, b.size * 0.62f, Offset.Zero)
                            2 -> {
                                tri.rewind()
                                tri.moveTo(0f, -b.size * 0.8f)
                                tri.lineTo(b.size * 0.75f, b.size * 0.6f)
                                tri.lineTo(-b.size * 0.75f, b.size * 0.6f)
                                tri.close()
                                drawPath(tri, b.colour)
                            }
                            else -> {
                                curl.rewind()
                                curl.moveTo(-b.size, 0f)
                                curl.quadraticTo(-b.size / 2, -b.size * 0.7f, 0f, 0f)
                                curl.quadraticTo(b.size / 2, b.size * 0.7f, b.size, 0f)
                                drawPath(curl, b.colour, style = Stroke(b.size * 0.36f, cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val COLOURS = with(RecapInk) { listOf(Lemon, Pink, Blue, Mint, Orange, Cream, Sky, Violet) }
    }
}
