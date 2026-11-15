/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A room's invite link opens the app, which asks before joining. */
class TogetherLinkTest {
    @Test
    fun `intertune together links reach MainActivity, which asks before joining`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // The app's own scheme on MainActivity takes intertune://together/<code> with every other link of its own.
        assertTrue("<data android:scheme=\"intertune\" />" in manifest)
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("TogetherInviteDialog(code, playerConnection," in main)
        assertTrue("RoomCode.isInviteLink(link)" in main)
    }

    @Test
    fun `the link carries the code`() {
        val code = RoomCode.generate()
        assertEquals("intertune://together/${code.raw}", code.appLink)
        assertEquals(code, RoomCode.parse(code.appLink))
    }

    @Test
    fun `share sends the https link, on its own line`() {
        val code = RoomCode.generate()
        assertEquals("https://intertune.app/together/${code.raw}", code.webLink)
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue("room.code.pretty, room.code.webLink" in dialog)
        for (res in listOf("values", "values-fr")) {
            val text = File("src/main/res/$res/strings-ot.xml").readText()
            val line = Regex("<string name=\"together_invite_text\">(.*)</string>").find(text)!!.groupValues[1]
            assertTrue("$res: the link goes on its own line", line.endsWith("\\n%2\$s"))
        }
    }

    @Test
    fun `both forms of the link are read`() {
        val code = RoomCode.generate()
        assertEquals(code, RoomCode.parse(code.webLink))
        assertEquals(code, RoomCode.parse(code.webLink.uppercase()))
        assertEquals(code, RoomCode.parse(code.webLink + "?utm=x"))
        assertEquals(code, RoomCode.parse(code.webLink + "/"))
        assertEquals(code, RoomCode.parse(code.appLink))
        assertTrue(RoomCode.isInviteLink(code.webLink))
        assertTrue(RoomCode.isInviteLink(code.appLink))
        assertTrue(!RoomCode.isInviteLink("https://intertune.app/s/v/abc"))
        assertEquals(null, RoomCode.parse("https://intertune.app/together/"))
        assertEquals(null, RoomCode.parse("https://intertune.app/together/short"))
    }

    @Test
    fun `the code field takes a whole shared message with the https link`() {
        val code = RoomCode.generate()
        val message = "Join my room on InterTune. Tap the link, or type the code ${code.pretty} under Listen together.\n${code.webLink}"
        assertEquals(code.raw, com.dd3boh.outertune.ui.dialog.codeTyped(message))
        val old = "Open ${code.appLink}, or type the code"
        assertEquals(code.raw, com.dd3boh.outertune.ui.dialog.codeTyped(old))
    }

    @Test
    fun `the app link filter takes the together path, on the verified host`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val filter = manifest.substringAfter("<data android:path=\"/s\" />").substringBefore("</intent-filter>")
        assertTrue("<data android:pathPrefix=\"/together/\" />" in filter)
        assertTrue("android:host=\"intertune.app\"" in manifest)
    }

    @Test
    fun `starting a room with no name asks for one first`() {
        val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
        assertTrue(com.dd3boh.outertune.ui.dialog.needsName(null))
        assertTrue(com.dd3boh.outertune.ui.dialog.needsName("  "))
        assertTrue(!com.dd3boh.outertune.ui.dialog.needsName("Mel"))
        assertTrue("if (needsName(context.dataStore.data.first()[TogetherNameKey])) askingName = true" in dialog.replace(Regex("\\s+"), " "))
        // Joining is left as it was: no ask.
        assertTrue("onJoin = { open(it, owner = false) }" in dialog)
        // The field is the setting's own title, and Continue saves into the same setting.
        assertTrue("title = { Text(stringResource(R.string.together_name_setting)) }" in dialog)
        assertTrue("it[TogetherNameKey] = name" in dialog)
    }

    @Test
    fun `an invite to the room this phone is in opens that room, it does not join again`() {
        val code = RoomCode.generate()
        val other = RoomCode.generate()
        // Hosting or following, the room is the same room: no question, no second join.
        assertEquals(InviteAnswer.Answer.OPEN_ROOM, InviteAnswer.forInvite(code, current = RoomCode.parse(code.webLink)))
        assertEquals(InviteAnswer.Answer.OPEN_ROOM, InviteAnswer.forInvite(RoomCode.parse(code.appLink)!!, current = code))
        // Another room, or none: asked as before.
        assertEquals(InviteAnswer.Answer.ASK, InviteAnswer.forInvite(code, current = other))
        assertEquals(InviteAnswer.Answer.ASK, InviteAnswer.forInvite(code, current = null))
    }

    @Test
    fun `the links and the service use that answer, and a second start of the same room changes nothing`() {
        val main = File("src/main/java/com/dd3boh/outertune/MainActivity.kt").readText()
        assertTrue("InviteAnswer.forInvite(invite," in main)
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        assertTrue("together.value?.takeIf { it.code == code }" in service)
    }
}
