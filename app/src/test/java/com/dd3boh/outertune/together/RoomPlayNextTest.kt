package com.dd3boh.outertune.together

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RoomPlayNextTest {
    private fun song(id: String, local: Boolean = false) =
        MediaMetadata(id = id, title = id, artists = emptyList(), duration = 1, thumbnailUrl = null, genre = null, isLocal = local)

    @Test
    fun `songs are planned in the order chosen and sent last-first for next, first-first for the end`() {
        val plan = RoomPlayNext.plan(listOf(song("a"), song("b"), song("c")))
        assertEquals(listOf("a", "b", "c"), plan.toRoom.map { it.id })
        assertEquals(listOf("c" to 2, "b" to 1, "a" to 0), RoomPlayNext.sendOrder(plan.toRoom, atEnd = false).map { it.value.id to it.index })
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), RoomPlayNext.sendOrder(plan.toRoom, atEnd = true).map { it.value.id to it.index })
    }

    @Test
    fun `a song from a file is left out and counted, and a long list is cut and counted`() {
        val plan = RoomPlayNext.plan(listOf(song("f", local = true)) + (1..40).map { song("s$it") })
        assertEquals(1, plan.localSkipped)
        assertEquals(RoomPlayNext.MAX, plan.toRoom.size)
        assertEquals("s1", plan.toRoom.first().id)
        assertEquals(10, plan.dropped)
    }

    @Test
    fun `a batch that arrives in any order comes out in play order`() {
        val c = RoomPlayNext.Collector<String>()
        assertEquals(true, c.isNew("k"))
        assertEquals(null, c.add("k", 2, 3, "c"))
        assertEquals(false, c.isNew("k"))
        assertEquals(null, c.add("k", 0, 3, "a"))
        assertEquals(listOf("a", "b", "c"), c.add("k", 1, 3, "b"))
        assertEquals(null, c.flush("k"))
    }

    @Test
    fun `a batch with a message missing is used as it is when the wait is over`() {
        val c = RoomPlayNext.Collector<String>()
        c.add("k", 3, 4, "d"); c.add("k", 1, 4, "b")
        assertEquals(listOf("b", "d"), c.flush("k"))
    }

    @Test
    fun `Suggest keeps its end flag and batch, and an old message decodes as one song next`() {
        val m = TogetherMessage.Suggest("x", "t", "a", "", "b", atEnd = true, batch = "ab12", index = 2, count = 5)
        assertEquals(m, TogetherMessage.decode(TogetherMessage.encode(m)))
        val old = TogetherMessage.decode("""{"t":"suggest","song":"x","title":"t","artist":"a","thumb":"","by":"b"}""")
        assertEquals(TogetherMessage.Suggest("x", "t", "a", "", "b"), old)
        // An older host reads the same keys it always did and ignores the new ones.
        val json = TogetherMessage.encode(m)
        for (k in listOf("song", "title", "artist", "thumb", "by")) assertEquals(true, json.contains("\"$k\""))
        // A batch count far too large is held down.
        val big = TogetherMessage.decode("""{"t":"suggest","song":"x","title":"t","artist":"a","thumb":"","by":"b","batch":"q","i":1,"n":100000}""")
        assertEquals(64, (big as TogetherMessage.Suggest).count)
    }

    @Test
    fun `the room's Play next is the only one, and the second menu item is gone`() {
        val root = File("src/main/java/com/dd3boh/outertune")
        for (f in listOf("ui/menu/SongMenu.kt", "ui/menu/YouTubeSongMenu.kt")) {
            assertEquals(f, false, File(root, f).readText().contains("R.string.together_suggest\n"))
        }
        val pc = File(root, "playback/PlayerConnection.kt").readText()
        assertEquals(true, pc.contains("RoomPlayNext.plan"))
        // Add to queue goes the same way, and the notice goes through Toaster.
        assertEquals(true, pc.contains("toRoom(items, atEnd = true)"))
        assertEquals(false, pc.contains("android.widget.Toast"))
    }
}
