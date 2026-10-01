/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.runtime.saveable.SaverScope
import com.dd3boh.outertune.utils.AutoBackupPolicy.KeepChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Serializable

/** The Keep dialog's question, saved when the activity is recreated and read back after. */
class KeepQuestionSaverTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun save(question: Pair<Int, KeepChange>?) = with(KeepQuestionSaver) { scope.save(question) }

    private fun roundTrip(question: Pair<Int, KeepChange>?): Pair<Int, KeepChange>? =
        save(question)?.let { KeepQuestionSaver.restore(it) }

    @Test
    fun `a counted question comes back with the names it would delete`() {
        val question = 5 to KeepChange.AskCount(
            listOf("InterTune_25_20260929224700.backup", "InterTune_25_20260929225000 (1).backup")
        )
        assertEquals(question, roundTrip(question))
    }

    @Test
    fun `a question without a count comes back without one`() {
        assertEquals(3 to KeepChange.AskUnknown, roundTrip(3 to KeepChange.AskUnknown))
    }

    @Test
    fun `no question keeps nothing`() {
        assertNull(save(null))
    }

    @Test
    fun `what is kept is what a Bundle holds`() {
        // An ArrayList of the Keep and, for a counted question, an ArrayList of the names.
        val counted = save(5 to KeepChange.AskCount(listOf("InterTune_25_20260929224700.backup")))
        assertNotNull(counted)
        assertEquals(listOf(5, arrayListOf("InterTune_25_20260929224700.backup")), counted)
        assertTrue(counted!!.all { it is Serializable } && counted[1] is ArrayList<*>)
        assertEquals(arrayListOf<Any>(3), save(3 to KeepChange.AskUnknown))
    }
}
