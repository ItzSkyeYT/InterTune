/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The menu of a song in the queue opens on the song's row (#219), and the artist's name on it opens the artist (his ask
 * of 27 Nov 2026: "also the artist name should be clickable in that menu!!"), as the artist line does in the player and
 * View artist does in every song menu: one artist straight away, several by the choice of them the app already has.
 * Read from the source, as the other menu tests are.
 */
class QueueMenuArtistTest {
    private val menu = File("src/main/java/com/dd3boh/outertune/ui/menu/PlayerMenu.kt").readText()

    /** The song's row on top of the menu, its heart included. */
    private val header = menu.substringAfter("if (songHeader) {").substringBefore("HorizontalDivider()")

    /** The way to the artist both the row and View artist take. */
    private val openArtist = menu.substringAfter("val openArtist: () -> Unit = {").substringBefore("\n    }\n")

    @Test
    fun `the artist's name on the song's row opens the artist, with the tick every menu entry gives`() {
        val name = header.substringAfter("text = artistNames,").substringBefore("if (length.isNotEmpty())")
        assertTrue(name, name.contains(".clickable(enabled = artists.isNotEmpty(), onClickLabel = stringResource(R.string.view_artist), role = Role.Button) {"))
        assertTrue(name, Regex("""haptic\.tapTick\(\)\s+openArtist\(\)""").containsMatchIn(name))
        // The length stays beside it, not part of the link, and is never pushed out by a long line of artists.
        assertTrue(name, name.contains(".weight(1f, fill = false)"))
        assertTrue(header.contains("val length = makeTimeString(mediaMetadata.duration * 1000L)"))
        assertTrue(header.contains("text = if (artistNames.isNotEmpty()) \" • \$length\" else length,"))
    }

    @Test
    fun `one artist opens at once, several are offered to pick from, the same as View artist`() {
        assertTrue(openArtist.contains("if (artists.size == 1) {"))
        assertTrue(openArtist.contains("ActivityLog.note(context, database, mediaMetadata.id, SignalKind.ARTIST_PAGE)"))
        assertTrue(openArtist.contains("navController.navigate(\"artist/\${artists[0].id}\")"))
        assertTrue("the player makes way for the artist's page", openArtist.contains("playerBottomSheetState.collapseSoft()"))
        assertTrue(openArtist.contains("onDismiss()"))
        assertTrue(openArtist.contains("showSelectArtistDialog = true"))
        val viewArtist = menu.substringAfter("title = R.string.view_artist").substringBefore("} else {")
        assertTrue(viewArtist, viewArtist.contains("onClick = openArtist"))
        // Only those two ways, so the row and the entry never drift apart.
        assertEquals(2, Regex("""\bopenArtist\b""").findAll(menu).count() - 1)
    }

    @Test
    fun `the heart stays on the row`() {
        assertTrue(header.contains("onClick = { likeFromQueue(database, playerConnection, mediaMetadata) }"))
    }
}
