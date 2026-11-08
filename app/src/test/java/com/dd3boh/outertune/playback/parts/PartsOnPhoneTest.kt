/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.parts

import android.content.Context
import android.content.ContextWrapper
import com.dd3boh.outertune.ui.screens.mix.PartsMaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ShortBuffer

/**
 * The debug app's maker of parts (PartsOnPhone, src/debug), up to the model, which needs the phone's
 * runtime: no maker without the model file, one model in the app at a time, a window made meanwhile
 * not split again, and the model's place let go however a split ends. Reached by name, as the
 * editor reaches it; a build without it skips this.
 */
class PartsOnPhoneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun context(files: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = files
    }

    private fun open(context: Context): PartsMaker? {
        val type = runCatching { Class.forName(PartsMaker.DEBUG_MAKER) }.getOrNull()
        assumeTrue("${PartsMaker.DEBUG_MAKER} is not in this build", type != null)
        return type!!.getMethod("open", Context::class.java).invoke(null, context) as PartsMaker?
    }

    /** A cancel that comes after [seconds], so a test that goes wrong ends instead of waiting for the lock. */
    private fun within(seconds: Int): () -> Boolean {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        return { System.nanoTime() > end }
    }

    private fun withModel(): File = tmp.newFolder().also { files ->
        File(files, "mix/models").mkdirs()
        File(files, "mix/models/scnet-int8.ort").writeText("not a model")
    }

    @Test
    fun `without the model file there is no maker`() {
        assertNull(open(context(tmp.newFolder())))
    }

    @Test
    fun `one model in the app at a time, and a window made meanwhile is not split again`() {
        val files = withModel()
        val window = ShortBuffer.wrap(ShortArray(100))
        val made = (0..3).map { File(tmp.root, "w.$it.pcm").apply { writeBytes(ByteArray(200)) } }
        val one = open(context(files))!!
        val two = open(context(files))!!
        try {
            // Already made: done, with no model loaded (none would load here).
            assertTrue(one.split(window, 48_000, 2, made, {}, within(10)))
            // The first holds the model's place until it is closed: the second waits, asking its cancel.
            var asked = 0
            assertFalse(two.split(window, 48_000, 2, made, {}, { ++asked >= 3 }))
            assertTrue(asked >= 3)
            one.close()
            assertTrue(two.split(window, 48_000, 2, made, {}, within(10)))
        } finally {
            one.close()
            two.close()
        }
    }

    @Test
    fun `a split that fails at the model lets its place go when closed`() {
        val files = withModel()
        val window = ShortBuffer.wrap(ShortArray(100))
        val failing = open(context(files))!!
        try {
            // The runtime here has no native library, and the file is no model: either way it throws.
            val missing = (0..3).map { File(tmp.root, "x.$it.pcm") }
            assertTrue(runCatching { failing.split(window, 48_000, 2, missing, {}, within(10)) }.isFailure)
            assertTrue(missing.none { it.exists() })
        } finally {
            failing.close()
        }
        val next = open(context(files))!!
        try {
            val made = (0..3).map { File(tmp.root, "y.$it.pcm").apply { writeBytes(ByteArray(200)) } }
            assertTrue(next.split(window, 48_000, 2, made, {}, within(10)))
        } finally {
            next.close()
        }
    }
}
