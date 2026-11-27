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
        assertEquals("https://lh3.googleusercontent.com/a", TogetherMessage.thumbnailFor(" HTTPS://lh3.googleusercontent.com/a "))
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

    /**
     * Any https address was kept, and every guest's phone loads the pictures of the room's queue and suggestions: whoever
     * has the code could learn each guest's address from a server of their own (review 2, 18 Nov 2026). Only YouTube's
     * own picture servers now, read as the picture loader reads them.
     */
    @Test
    fun `a picture travels only from YouTube's own picture servers`() {
        for (kept in listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://i9.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            "https://yt3.googleusercontent.com/abc=s176-c-k",
            "https://yt3.ggpht.com/abc=s88-c-k",
        )) {
            assertEquals(kept, TogetherMessage.thumbnailFor(kept))
        }
        for (refused in listOf(
            "https://their.server.example/cover.jpg",
            "https://i.ytimg.com.their.server.example/vi/x/hqdefault.jpg",
            "https://i.ytimg.com@their.server.example/vi/x/hqdefault.jpg",
            "https://their.server.example\\@i.ytimg.com/vi/x/hqdefault.jpg",
            "https://their.server.example/?u=https://i.ytimg.com/vi/x/hqdefault.jpg",
            "https://doc-0s-1c-docs.googleusercontent.com/x",
            "http://i.ytimg.com/vi/x/hqdefault.jpg",
        )) {
            assertEquals(refused, "", TogetherMessage.thumbnailFor(refused))
        }
        // and as it is read from a room
        val raw = """{"t":"suggest","song":"x","title":"t","artist":"a","thumb":"https://their.server.example/a.jpg","by":"b"}"""
        assertEquals("", (TogetherMessage.decode(raw) as TogetherMessage.Suggest).thumbnail)
    }

    /**
     * The path was gone, but a local song still went to the room by name, and an untagged file's name is its title
     * ("Recording 2026-11-18 call with Jane"): in the queue every 30 s and in a guest's proposal, shown in every guest's
     * queue though no guest can play it (review 2, 18 Nov 2026). Local songs stayed out of both. Since 22 Nov 2026 one
     * goes in as the same recording on YouTube Music when its tags find one (PhoneSongs): the match's id, name and cover,
     * never the file's own (PhoneSongsTest).
     */
    @Test
    fun `songs from files on the phone go to the room's queue only as their match on YouTube Music`() {
        // by what the app knows of the song, or failing that by a local id's shape
        assertTrue(fromThisPhone(isLocal = true, mediaId = "LSabcdefgh"))
        assertTrue(fromThisPhone(isLocal = null, mediaId = "LSabcdefgh"))
        assertTrue(fromThisPhone(isLocal = null, mediaId = "LSab12efgh"))
        assertFalse(fromThisPhone(isLocal = false, mediaId = "dQw4w9WgXcQ"))
        assertFalse(fromThisPhone(isLocal = null, mediaId = "dQw4w9WgXcQ"))
        // a YouTube id may start with LS, but it has eleven characters
        assertFalse(fromThisPhone(isLocal = null, mediaId = "LSabcdefghi"))
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        val own = session.substringAfter("private fun ownQueue()").substringBefore("private fun send(")
        assertTrue("if (!fromThisPhone(item.metadata?.isLocal, item.mediaId)) {" in own)
        assertTrue(own.indexOf("if (!fromThisPhone(") < own.indexOf("songs += TogetherMessage.Song("))
        // A song from a file adds its match and nothing of its own: no id, name or picture of the item.
        val fromFile = own.substringAfter("} else {").substringBefore("index = timeline.getNextWindowIndex(")
        assertTrue("if (heard is PhoneSongs.Heard) songs += heard.song" in fromFile)
        for (part in listOf("TogetherMessage.Song(", "item.mediaMetadata", "metadata.title", "artworkUri", "localPath")) {
            assertFalse(part, part in fromFile)
        }
        // a guest proposes its own song first or nothing (the proposal is built from ownQueue)
        val propose = session.substringAfter("fun propose()").substringBefore("\n    }\n")
        assertTrue("it.isNotEmpty() && it.first().id == service.exoPlayer.currentMediaItem?.mediaId" in propose)
        // and the sheet offers no Propose at all since 26 Nov 2026 (guests listen, or leave: Spotify's Jam)
        val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertFalse("onPropose" in sheet)
    }

    @Test
    fun `a guest waits for no queue when the host plays a song from its phone`() {
        val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        // That song is never first in the host's queue, so a wait only played the next song's start.
        assertTrue("if (queue == null && !fromThisPhone(null, state.songId)) {" in session)
        // A song from a phone is told by its id alone, as the host's State carries it.
        assertTrue(fromThisPhone(null, "LS0a1B2c3D"))
        assertFalse(fromThisPhone(null, "s7-B-JXmhOs"))
        // The leader's queue wakes the look, so a song waiting for it is taken at once.
        val queueBranch = session.substringAfter("is TogetherMessage.Queue -> if (!leading) {").substringBefore("is TogetherMessage.")
        assertTrue("wake.trySend(Unit)" in queueBranch.substringAfter("roomQueue = message"))
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
