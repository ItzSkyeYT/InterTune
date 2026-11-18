/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song from a file on the phone has the file's full path as its picture (the scanners set it so), and the host's
 * queue sent it to the whole room: up to 30 paths like /storage/emulated/0/Music/<folders>/<file>, to anyone with the
 * code, who cannot play those songs anyway (review of 0.13, 18 Nov 2026). Now a room carries a picture only as a web
 * address.
 */
class RoomLocalFilesTest {

    private val path = "/storage/emulated/0/Music/Mel Boro/Voice memos/hello.mp3"
    private val web = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"

    @Test
    fun `a song's picture travels only as a web address`() {
        assertEquals(web, TogetherMessage.thumbnailFor(web))
        assertEquals("HTTPS://lh3.googleusercontent.com/a", TogetherMessage.thumbnailFor(" HTTPS://lh3.googleusercontent.com/a "))
        for (local in listOf(path, "file://$path", "content://media/external/audio/albumart/12", "http://192.168.1.20:8080/cover.jpg", "", " ", null)) {
            assertEquals("for $local", "", TogetherMessage.thumbnailFor(local))
        }
    }

    @Test
    fun `a host's queue, a guest's proposal and a suggestion carry no file's path`() {
        val songs = listOf(
            TogetherMessage.Song("LSa1b2c3d4", "hello", "Mel", path),
            TogetherMessage.Song("dQw4w9WgXcQ", "Never", "Rick", web),
        )
        val sent = listOf(
            TogetherMessage.Queue(songs, repeat = 0, shuffle = false),
            TogetherMessage.Propose(songs, positionMs = 1_000, by = "Pixel"),
            TogetherMessage.Suggest("LSa1b2c3d4", "hello", "Mel", path, "Pixel"),
        )
        for (message in sent) {
            val text = TogetherMessage.encode(message)
            assertFalse(text, "/storage" in text)
            assertFalse(text, "Voice memos" in text)
        }
        val queue = TogetherMessage.decode(TogetherMessage.encode(sent[0])) as TogetherMessage.Queue
        // the song itself is still there, by its name, and a web picture stays as it was
        assertEquals(listOf("LSa1b2c3d4", "dQw4w9WgXcQ"), queue.songs.map { it.id })
        assertEquals(listOf("hello", "Never"), queue.songs.map { it.title })
        assertEquals(listOf("", web), queue.songs.map { it.thumbnail })
        val proposal = TogetherMessage.decode(TogetherMessage.encode(sent[1])) as TogetherMessage.Propose
        assertEquals(listOf("", web), proposal.songs.map { it.thumbnail })
        assertEquals("", (TogetherMessage.decode(TogetherMessage.encode(sent[2])) as TogetherMessage.Suggest).thumbnail)
    }

    @Test
    fun `a path that comes from a room is not taken as a picture on this phone`() {
        val raw = """{"t":"queue","repeat":0,"shuffle":false,"songs":[{"id":"x","title":"t","artist":"a","thumb":"/sdcard/Pictures/me.jpg"}]}"""
        assertEquals("", (TogetherMessage.decode(raw) as TogetherMessage.Queue).songs.single().thumbnail)
        val suggest = """{"t":"suggest","song":"x","title":"t","artist":"a","thumb":"file:///sdcard/a.jpg","by":"b"}"""
        assertEquals("", (TogetherMessage.decode(suggest) as TogetherMessage.Suggest).thumbnail)
    }

    @Test
    fun `the session makes its queue and its suggestions with it`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        val own = session.substringAfter("private fun ownQueue()").substringBefore("private fun send(")
        assertTrue("thumbnail = TogetherMessage.thumbnailFor(metadata.artworkUri?.toString())" in own)
        assertFalse("artworkUri?.toString().orEmpty()" in session)
        assertTrue("TogetherMessage.thumbnailFor(song.thumbnailUrl)" in session.substringAfter("fun suggest(").substringBefore("fun propose("))
    }
}
