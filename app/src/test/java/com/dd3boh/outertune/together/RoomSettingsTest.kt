/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The host's room settings, starting with moving in the song (#testers, 22 Nov 2026). Ti-Die, a guest: "wait when i
 * change the timeline does it do it for everyone", "is that ideal to me it feels wrong"; dvd: "maybe the host should
 * have a settings button to change the lobby settings, to configure who can do what", and his answer: "Agreed"; in
 * his form the same evening, for a guest's seek bar, "Lock, host can unlock". So the owner's Here says who may move in
 * the song, off by default; a guest following the room then moves nothing and is told why, and builds on either side
 * of this one carry on as rooms were.
 */
class RoomSettingsTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()

    /** A guest that has heard the leader, following in step. */
    private val guest = TogetherSession.Status(waitingForHost = false)
    private val listens = RoomSettings(everyoneControls = false)
    private val controls = RoomSettings(everyoneControls = true)

    /** His word of 26 Nov 2026, Spotify's Jam: guests listen, and the host can switch on control for everyone. */
    @Test
    fun `a new room lets only the person in control control the music`() {
        assertFalse(RoomSettings().everyoneControls)
        assertTrue("settings = if (owner) RoomSettings() else null" in session)
        // The owner's switch as kept, the two before it read once: either on is it on.
        assertTrue("RoomSettings.kept(prefs[TogetherEveryoneControlsKey], prefs[TogetherEveryoneSeeksKey], prefs[TogetherEveryoneArrangesKey])" in session)
        assertFalse(RoomSettings.kept(null, null, null))
        assertFalse(RoomSettings.kept(null, false, false))
        assertTrue(RoomSettings.kept(null, true, false))
        assertTrue(RoomSettings.kept(null, false, true))
        assertFalse(RoomSettings.kept(false, true, true))
        assertTrue(RoomSettings.kept(true, false, false))
    }

    @Test
    fun `the owner's settings go in its Here, and come out the same`() {
        val here = TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = listens)
        assertEquals(here, TogetherMessage.decodeFrom(TogetherMessage.encode(here, sender = "ab12"), "ab12"))
        val open = here.copy(settings = controls)
        assertEquals(open, TogetherMessage.decodeFrom(TogetherMessage.encode(open, sender = "ab12"), "ab12"))
    }

    @Test
    fun `a guest on a build before this one reads the two switches it knows`() {
        val text = TogetherMessage.encode(TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = controls), sender = "ab12")
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals("here", o["t"]!!.jsonPrimitive.content)
        val settings = o["settings"]!!.jsonObject
        assertTrue(settings["control"]!!.jsonPrimitive.boolean)
        assertTrue(settings["seek"]!!.jsonPrimitive.boolean)
        assertTrue(settings["arrange"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an owner on an older build says the two switches before it, either on being control for everyone`() {
        val old = """{"t":"here","name":"Ti-Die","owner":true,"lead":"ab12","k":"ab12"}"""
        assertNull((TogetherMessage.decodeFrom(old, "ab12") as TogetherMessage.Here).settings)
        assertNull(RoomSettings.of(JsonPrimitive("seek")))
        assertNull(RoomSettings.of(null))
        assertFalse(RoomSettings.of(JsonObject(emptyMap()))!!.everyoneControls)
        assertTrue(RoomSettings.of(JsonObject(mapOf("seek" to JsonPrimitive(true))))!!.everyoneControls)
        assertTrue(RoomSettings.of(JsonObject(mapOf("arrange" to JsonPrimitive(true))))!!.everyoneControls)
        assertFalse(RoomSettings.of(JsonObject(mapOf("seek" to JsonPrimitive(false), "arrange" to JsonPrimitive(false))))!!.everyoneControls)
        assertFalse(RoomSettings.of(JsonObject(mapOf("control" to JsonPrimitive(false), "seek" to JsonPrimitive(true))))!!.everyoneControls)
    }

    @Test
    fun `a guest listens unless the host lets everyone control the music`() {
        val guest = guest.copy(leaderControls = true)
        assertTrue(listensOnly(guest.copy(settings = listens)))
        assertTrue(listensOnly(guest))
        // Waiting for the room, or nobody playing for it: nothing it does here is the room's.
        assertTrue(listensOnly(guest.copy(waitingForHost = true)))
        assertTrue(listensOnly(guest.copy(settings = listens, pausedHere = true)))
        assertFalse(listensOnly(guest.copy(settings = controls)))
        assertFalse(listensOnly(guest.copy(leading = true)))
        assertFalse(listensOnly(guest.copy(casting = true)))
        // Its seeks are held while it listens, never while it controls the music with everyone.
        assertTrue(seekHeld(guest.copy(settings = listens)))
        assertFalse(seekHeld(guest.copy(settings = controls)))
        assertTrue(guestControls(guest.copy(settings = controls)))
        assertFalse(guestControls(guest.copy(settings = controls, waitingForHost = true)))
        assertFalse(guestControls(guest.copy(settings = controls, leading = true)))
        assertFalse(guestControls(guest.copy(settings = listens)))
        // Under one in control on a build before 27 Nov 2026, which drops a guest's moves: it listens, and is told why.
        val older = guest.copy(settings = controls, leaderControls = false)
        assertFalse(guestControls(older))
        assertTrue(listensOnly(older))
        assertTrue(controlFromOlder(older))
        assertFalse(controlFromOlder(guest.copy(settings = controls)))
        assertFalse(controlFromOlder(older.copy(waitingForHost = true)))
        // Its queue changes still go to one in control that takes them (Arrange, since 22 Nov 2026).
        assertTrue(guestArranges(older.copy(leaderArranges = true)))
    }

    @Test
    fun `the one in control says it takes a guest's moves, and a build before it says nothing`() {
        val here = TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = controls, arranges = true, controls = true)
        assertEquals(here, TogetherMessage.decodeFrom(TogetherMessage.encode(here, sender = "ab12"), "ab12"))
        val old = """{"t":"here","name":"Ti-Die","owner":true,"lead":"ab12","arranges":true,"k":"ab12"}"""
        assertFalse((TogetherMessage.decodeFrom(old, "ab12") as TogetherMessage.Here).controls)
        // Every Here this build says, the host's handing-on word included.
        assertEquals(2, Regex("""arranges = true, dev = devProof, takesRoom = true,(\s*host = handing\.to,)?\s*controls = true,""").findAll(session).count())
        assertTrue("leaderControls = leaderKey.let { it != null && (it == me || it in controllers) }," in session)
    }

    @Test
    fun `only the owner's word sets them, and everyone hears a change at once`() {
        // Said by the owner alone, in every Here, a newcomer's greeting included.
        assertTrue("settings = if (owner) _status.value.settings else null, arranges = true," in session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n"))
        // Taken from the owner's word alone, never from a guest's, nor by the owner from anyone.
        assertTrue("if (!owner && from == ownerKey && message.settings != _status.value.settings) {" in session)
        val own = session.substringAfter("private fun ownSettings(settings: RoomSettings) {").substringBefore("\n    }\n")
        assertTrue("sayHere()" in own)
    }

    @Test
    fun `the room's sheet and Settings share the one switch, and the owner's room follows it`() {
        val sheet = File(src, "ui/dialog/RoomSettingsSwitches.kt").readText()
        val settings = File(src, "ui/screens/settings/ListenTogetherSettings.kt").readText()
        for ((name, text) in listOf("the room's sheet" to sheet, "Settings > Listen together" to settings)) {
            assertTrue(name, "rememberPreference(TogetherEveryoneControlsKey, defaultValue = " in text)
            assertTrue(name, "RoomSettings.kept(null, context.dataStore[TogetherEveryoneSeeksKey], context.dataStore[TogetherEveryoneArrangesKey])" in text)
            assertFalse(name, "TogetherEveryoneSeeksKey, defaultValue" in text)
        }
        assertFalse("it[TogetherEveryoneControlsKey] =" in session)
        assertTrue("modifier = Modifier.settingRow(R.string.together_everyone_controls)," in settings)
        assertTrue("description = stringResource(R.string.together_everyone_controls_explain)," in settings)
        assertTrue("explain = stringResource(R.string.together_everyone_controls_explain)," in sheet)
        val index = com.dd3boh.outertune.ui.screens.settings.SETTINGS_INDEX.filter { it.route == "settings/together" }.map { it.title }
        assertTrue(com.dd3boh.outertune.R.string.together_everyone_controls in index)
    }

    @Test
    fun `a held move in the song keeps the guest with the room, and a guest with control moves it for everyone`() {
        val moved = session.substringAfter("override fun onPositionDiscontinuity(").substringBefore("\n        }\n")
        assertTrue("oldPosition.mediaItemIndex == newPosition.mediaItemIndex && seekHeld(_status.value)" in moved)
        assertTrue("seekHeldHere(abs(newPosition.positionMs - oldPosition.positionMs))" in moved)
        assertTrue("movedInSong(newPosition.positionMs)" in moved)
        val held = session.substringAfter("private fun seekHeldHere(movedMs: Long) {").substringBefore("\n    }\n")
        assertTrue(held.indexOf("if (System.currentTimeMillis() < ownCommandUntil) return") < held.indexOf("wake.trySend(Unit)"))
        assertTrue("Toaster.show(service, seekHeldLine(service, _status.value))" in held)
        assertFalse("ownWay = true" in held)
    }

    @Test
    fun `the room sheet shows the switches to the owner alone`() {
        val sheet = File(src, "ui/dialog/TogetherDialog.kt").readText()
        val part = sheet.substringAfter("// The host's own switches for the room").substringBefore("Spacer(Modifier.height(24.dp))")
        assertTrue("if (room.owner) {" in part)
        assertTrue("RoomSettingsSwitches()" in part)
        val switches = File(src, "ui/dialog/RoomSettingsSwitches.kt").readText()
        // The whole row is the switch, read as one by TalkBack.
        assertTrue(".toggleable(value = on, role = Role.Switch, onValueChange = onChange)" in switches)
        assertTrue("Switch(checked = on, onCheckedChange = null)" in switches)
    }
}
