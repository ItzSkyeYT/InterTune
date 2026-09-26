/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginScreenTest {

    @Test
    fun `an account picked opens the page for it, whatever was picked before`() {
        assertEquals("a@example.com", accountAfterPicker(previous = null, picked = "a@example.com"))
        assertEquals("b@example.com", accountAfterPicker(previous = "a@example.com", picked = "b@example.com"))
        assertEquals("b@example.com", accountAfterPicker(previous = "", picked = "b@example.com"))
    }

    @Test
    fun `a Switch dismissed keeps the account picked before it`() {
        assertEquals("a@example.com", accountAfterPicker(previous = "a@example.com", picked = null))
        // Back from the picker with no account in it is the same as dismissing it.
        assertEquals("a@example.com", accountAfterPicker(previous = "a@example.com", picked = ""))
    }

    @Test
    fun `the first picker dismissed opens the page with nothing filled in`() {
        assertEquals("", accountAfterPicker(previous = null, picked = null))
        assertEquals("", accountAfterPicker(previous = null, picked = ""))
    }

    @Test
    fun `Pick one dismissed after Another account leaves the page as it is`() {
        assertEquals("", accountAfterPicker(previous = "", picked = null))
    }
}
