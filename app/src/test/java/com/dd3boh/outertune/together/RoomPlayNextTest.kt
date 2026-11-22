package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RoomPlayNextTest {
    private fun song(id: String, local: Boolean = false) =
        MediaMetadata(id = id, title = id, artists = emptyList(), duration = 1, thumbnailUrl = null, genre = null, isLocal = local)

    @Test
    fun `songs go to the room in reverse so that they play in the order chosen`() {
        val plan = RoomPlayNext.plan(listOf(song("a"), song("b"), song("c")))
        assertEquals(listOf("c", "b", "a"), plan.toRoom.map { it.id })
    }

    @Test
    fun `a song from a file stays on this phone and a long list is cut`() {
        val plan = RoomPlayNext.plan(listOf(song("f", local = true)) + (1..40).map { song("s$it") })
        assertEquals(listOf("f"), plan.own.map { it.id })
        assertEquals(RoomPlayNext.MAX, plan.toRoom.size)
        assertEquals("s1", plan.toRoom.last().id)
        assertEquals(10, plan.dropped)
    }

    @Test
    fun `the room's Play next is the only one, and the second menu item is gone`() {
        val root = File("src/main/java/com/dd3boh/outertune")
        for (f in listOf("ui/menu/SongMenu.kt", "ui/menu/YouTubeSongMenu.kt")) {
            assertEquals(f, false, File(root, f).readText().contains("R.string.together_suggest\n"))
        }
        assertEquals(true, File(root, "playback/PlayerConnection.kt").readText().contains("RoomPlayNext.plan"))
    }
}
