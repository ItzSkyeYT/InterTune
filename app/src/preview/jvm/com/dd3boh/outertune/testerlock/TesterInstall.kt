/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import java.security.SecureRandom

/**
 * The number an install makes for itself the first time the tester lock is asked about a code.
 *
 * It is what lets the door keep a code to one device: the door holds a code for the install number
 * that first used it, and refuses the same code from any other number. It is random and nothing
 * more: 128 bits from the system's secure generator, never read from the phone, so no hardware id,
 * no Android id and nothing that names the phone or its owner. It is kept with the rest of the
 * lock's memory (TesterMemory), so reinstalling the app makes a new one, and the tester moves their
 * code to the new install with /code on Discord.
 */
object TesterInstall {
    const val BYTES = 16
    const val LENGTH = BYTES * 2

    /** 32 lower-case hex digits, the shape the door accepts. */
    fun make(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return buildString(LENGTH) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                append(DIGITS[v shr 4])
                append(DIGITS[v and 0x0f])
            }
        }
    }

    fun isWellFormed(install: String): Boolean = install.length == LENGTH && install.all { it in DIGITS }

    private const val DIGITS = "0123456789abcdef"
}
