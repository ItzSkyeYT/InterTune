/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gate's code field, read from its source: the gate is in the preview build type, which the unit
 * tests do not compile, and the emulator is where it was seen (experiments/features/tester-code).
 */
class TesterGateTest {

    private val gate = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterGate.kt").readText()
    private val lock = File("src/preview/java/com/dd3boh/outertune/testerlock/TesterLock.kt").readText()

    @Test
    fun `the answer under the field goes as soon as the code is changed`() {
        // Every change of the typed text after the first value, not a move of the cursor and not the
        // code the gate was opened with.
        assertTrue("snapshotFlow { field.text.toString() }.drop(1).collect { onEdited() }" in gate)
        assertTrue("onEdited = session::edited" in lock)
        val edited = lock.substringAfter("fun edited() {").substringBefore("\n    }\n")
        assertTrue(edited, "shown.value = gate.copy(message = null)" in edited)
        // Not while a check is under way: its answer is still to come.
        assertTrue(edited, "if (gate.busy || gate.message == null) return" in edited)
    }

    @Test
    fun `the keyboard offers no words for a code, and the code stays in sight`() {
        val options = gate.substringAfter("keyboardOptions = KeyboardOptions(").substringBefore("),")
        // To the keyboard a password field: Gboard and the others then suggest nothing and learn nothing.
        assertEquals(listOf("Password"), Regex("keyboardType = KeyboardType\\.(\\w+)").findAll(options).map { it.groupValues[1] }.toList())
        assertTrue(options, "autoCorrectEnabled = false" in options)
        // In Compose only a transformation hides text: the field draws the code itself, and nothing masks it.
        assertTrue("outputTransformation = CodeAsSent" in gate)
        for (masking in listOf("SecureTextField", "TextObfuscationMode", "PasswordVisualTransformation", "visualTransformation")) {
            assertFalse(masking, masking in gate)
        }
        // And what is typed is never changed under the keyboard (the crash of 29 Oct).
        assertFalse("inputTransformation" in gate)
    }
}
