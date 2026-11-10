/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When no extractor could read a stream, a player error says what the server sent instead: its
 * type, its length and how it began, kept per song from the stream's start only.
 */
class StreamAnswerTest {
    /** A server answer of [type] with [body], read [chunk] bytes at a time. */
    private class Answer(private val type: String?, private val body: ByteArray, private val chunk: Int = 5) : DataSource {
        private var at = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long { at = dataSpec.position.toInt(); return body.size - dataSpec.position }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= body.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, chunk, body.size - at)
            System.arraycopy(body, at, buffer, offset, n); at += n; return n
        }
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = type?.let { mapOf("content-type" to listOf(it)) } ?: emptyMap()
        override fun close() {}
    }

    /** A DataSpec with only its key and position: made without its constructor, which wants an Android Uri. */
    private fun spec(key: String, position: Long): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val spec = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
        DataSpec::class.java.getField("key").apply { isAccessible = true }.set(spec, key)
        DataSpec::class.java.getField("position").apply { isAccessible = true }.set(spec, position)
        return spec
    }

    private fun play(song: String, answer: Answer, position: Long = 0) {
        val source = StreamAnswer.Source(answer)
        source.open(spec(song, position))
        val buffer = ByteArray(64)
        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        source.close()
    }

    @Test
    fun `a web page instead of music is said as the text it began with`() {
        play("page", Answer("text/html; charset=utf-8", "<!DOCTYPE html>\n<html><body>Sorry</body></html>".toByteArray()))
        assertEquals("text/html, 47 bytes, began \"<!DOCTYPE html> \"", StreamAnswer.of("page"))
    }

    @Test
    fun `an empty answer says it ended with no bytes`() {
        play("empty", Answer("audio/webm", ByteArray(0)))
        assertEquals("audio/webm, 0 bytes, ended with no bytes", StreamAnswer.of("empty"))
    }

    @Test
    fun `music is said as its first bytes in hex`() {
        val webm = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(40) { 0x42 }
        play("music", Answer("audio/webm", webm))
        val line = StreamAnswer.of("music")!!
        assertTrue(line, line.startsWith("audio/webm, 44 bytes, began 1a 45 df a3 42"))
        assertEquals("16 bytes kept", 16, line.substringAfter("began ").split(" ").size)
    }

    @Test
    fun `a later range of a song keeps what its start was`() {
        play("ranged", Answer(null, "<html>".toByteArray()))
        play("ranged", Answer("audio/webm", ByteArray(100) { 1 }), position = 50)
        assertEquals("no type, 6 bytes, began \"<html>\"", StreamAnswer.of("ranged"))
        assertNull(StreamAnswer.of("never played"))
        assertNull(StreamAnswer.of(null))
    }

    @Test
    fun `the player's streams are the ones noted and the error's details print it`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("StreamAnswer.Factory(SabrOrHttp.Factory(OkHttpDataSource.Factory(calls), cutWith = calls))" in service)
        val screen = File("src/main/java/com/dd3boh/outertune/ui/player/ThumbnailPlaybackError.kt").readText()
        assertTrue("StreamAnswer.of(mediaId)?.let { \"stream answer: \$it\" }" in screen)
        assertTrue("streamLines(song).forEach { appendLine(it) }" in screen)
    }
}
