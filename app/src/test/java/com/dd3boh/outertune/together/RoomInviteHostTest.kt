/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 26 Nov 2026 (the whole-app scan, rooms finding 4): a host who opened another room's invite closed
 * its own room for everyone with one Join, unwarned, while its own Leave asks first and offers the
 * hand-on. The invite now says so on a phone that hosts, and how to keep the room going.
 */
class RoomInviteHostTest {

    @Test
    fun `a host is told that joining closes its room, and how to keep it`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        val invite = dialog.substringAfter("fun TogetherInviteDialog(").substringBefore("\n}\n")
        assertTrue(invite, "val session by playerConnection.service.together.collectAsState()\n    val hosting = session?.owner == true" in invite)
        assertTrue(invite, "if (hosting) Text(stringResource(R.string.together_join_closes_yours)" in invite)
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        assertTrue("closes yours for everyone. To keep it going, hand it on first with Leave." in en)
        assertTrue("ferme le vôtre pour tout le monde" in fr && "avec Quitter." in fr)
    }
}
