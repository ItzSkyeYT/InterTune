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
    private val locked = RoomSettings(everyoneSeeks = false)

    @Test
    fun `a new room lets only the person in control move in the song`() {
        assertFalse(RoomSettings().everyoneSeeks)
        assertFalse(session.contains("RoomSettings(everyoneSeeks = true)"))
        // The owner starts with them, then with what it kept from its last room.
        assertTrue("settings = if (owner) RoomSettings() else null" in session)
        assertTrue("everyoneSeeks = stored?.get(TogetherEveryoneSeeksKey) == true," in session)
    }

    @Test
    fun `the owner's settings go in its Here, and come out the same`() {
        val here = TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = locked)
        val text = TogetherMessage.encode(here, sender = "ab12")
        assertEquals(here, TogetherMessage.decodeFrom(text, "ab12"))
        val open = here.copy(settings = RoomSettings(everyoneSeeks = true))
        assertEquals(open, TogetherMessage.decodeFrom(TogetherMessage.encode(open, sender = "ab12"), "ab12"))
    }

    @Test
    fun `a build before this one reads the Here as it always did`() {
        val text = TogetherMessage.encode(TogetherMessage.Here("Ti-Die", owner = true, lead = "ab12", settings = locked), sender = "ab12")
        val o = Json.parseToJsonElement(text).jsonObject
        // What it reads, by name, untouched; the settings are one more field of their own, which it never asks for.
        assertEquals("here", o["t"]!!.jsonPrimitive.content)
        assertEquals("Ti-Die", o["name"]!!.jsonPrimitive.content)
        assertTrue(o["owner"]!!.jsonPrimitive.boolean)
        assertEquals("ab12", o["lead"]!!.jsonPrimitive.content)
        assertTrue(o["settings"] is JsonObject)
    }

    @Test
    fun `an owner on an older build says nothing of them, and its room stays as rooms were`() {
        val old = """{"t":"here","name":"Ti-Die","owner":true,"lead":"ab12","k":"ab12"}"""
        val here = TogetherMessage.decodeFrom(old, "ab12") as TogetherMessage.Here
        assertNull(here.settings)
        assertFalse(seekHeld(guest.copy(settings = here.settings)))
        // A word that says them in a way this build cannot read is the same, and a switch it does not say is as before.
        assertNull(RoomSettings.of(JsonPrimitive("seek")))
        assertNull(RoomSettings.of(null))
        assertTrue(RoomSettings.of(JsonObject(emptyMap()))!!.everyoneSeeks)
        assertFalse(RoomSettings.of(JsonObject(mapOf("seek" to JsonPrimitive(false))))!!.everyoneSeeks)
        assertTrue(RoomSettings.of(JsonObject(mapOf("seek" to JsonPrimitive("yes"))))!!.everyoneSeeks)
    }

    @Test
    fun `a guest following the room is held, and nobody else`() {
        assertTrue(seekHeld(guest.copy(settings = locked)))
        // Paused here it is still with the room: play takes it back to where the room is.
        assertTrue(seekHeld(guest.copy(settings = locked, pausedHere = true)))
        // The host lets everyone, or said nothing of it.
        assertFalse(seekHeld(guest.copy(settings = RoomSettings(everyoneSeeks = true))))
        assertFalse(seekHeld(guest))
        // The person in control moves the room's song; one on its own way moves in its own; one casting is on its own.
        assertFalse(seekHeld(guest.copy(settings = locked, leading = true)))
        assertFalse(seekHeld(guest.copy(settings = locked, ownWay = true)))
        assertFalse(seekHeld(guest.copy(settings = locked, casting = true)))
        // Before the leader's word there is nothing to hold it to.
        assertFalse(seekHeld(guest.copy(settings = locked, waitingForHost = true)))
    }

    @Test
    fun `a guest following the room has the room's shuffle and repeat, whatever the settings`() {
        // The person in control's next word puts them back whatever is changed here: never the guest's to change.
        assertTrue(followsRoom(guest))
        assertTrue(followsRoom(guest.copy(pausedHere = true)))
        assertTrue(followsRoom(guest.copy(settings = RoomSettings(everyoneSeeks = true))))
        assertFalse(followsRoom(guest.copy(leading = true)))
        assertFalse(followsRoom(guest.copy(ownWay = true)))
        assertFalse(followsRoom(guest.copy(casting = true)))
        assertFalse(followsRoom(guest.copy(waitingForHost = true)))
    }

    @Test
    fun `only the owner's word sets them, and everyone hears a change at once`() {
        // Said by the owner alone, in every Here, a newcomer's greeting included.
        assertTrue("settings = if (owner) _status.value.settings else null, arranges = true," in session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n"))
        // Taken from the owner's word alone, never from a guest's, nor by the owner from anyone.
        assertTrue("if (!owner && from == ownerKey && message.settings != _status.value.settings) {" in session)
        val own = session.substringAfter("private fun ownSettings(settings: RoomSettings) {").substringBefore("\n    }\n")
        assertTrue("sayHere()" in own)
        val switch = session.substringAfter("fun letEveryoneSeek(on: Boolean) {").substringBefore("\n    }\n")
        assertTrue("if (!owner) return" in switch)
        // The other switches as they are.
        assertTrue("ownSettings((_status.value.settings ?: RoomSettings()).copy(everyoneSeeks = on))" in switch)
        assertTrue("it[TogetherEveryoneSeeksKey] = on" in switch)
        // Kept for the next room: a failure to keep it never ends this one (the room's failure handler would).
        assertTrue("runCatching { service.dataStore.edit { it[TogetherEveryoneSeeksKey] = on } }" in switch)
    }

    @Test
    fun `a held move in the song keeps the guest with the room, a song of its own is still its own way`() {
        val moved = session.substringAfter("override fun onPositionDiscontinuity(").substringBefore("\n        }\n")
        assertTrue("oldPosition.mediaItemIndex == newPosition.mediaItemIndex && seekHeld(_status.value)" in moved)
        assertTrue("seekHeldHere(abs(newPosition.positionMs - oldPosition.positionMs))" in moved)
        assertTrue("} else listenerChose(\"a seek\")" in moved)
        val held = session.substringAfter("private fun seekHeldHere(movedMs: Long) {").substringBefore("\n    }\n")
        // Never the room's own seeks; the next look puts it back; a move in place (an effect switched on) says nothing.
        assertTrue(held.indexOf("if (System.currentTimeMillis() < ownCommandUntil) return") < held.indexOf("wake.trySend(Unit)"))
        assertTrue("if (movedMs < HELD_SAID_MS) return" in held)
        assertTrue("Toaster.show(service, seekHeldLine(service, _status.value))" in held)
        assertFalse("ownWay = true" in held)
    }

    @Test
    fun `the room sheet shows the switches to the owner alone`() {
        val sheet = File(src, "ui/dialog/TogetherDialog.kt").readText()
        val part = sheet.substringAfter("// The host's own switches for the room").substringBefore("Spacer(Modifier.height(24.dp))")
        assertTrue("if (room.owner) {" in part)
        assertTrue("RoomSettingsSwitches(status.settings, onEveryoneSeeks = room::letEveryoneSeek, onEveryoneArranges = room::letEveryoneArrange)" in part)
        val switches = File(src, "ui/dialog/RoomSettingsSwitches.kt").readText()
        // The whole row is the switch, read as one by TalkBack.
        assertTrue(".toggleable(value = on, role = Role.Switch, onValueChange = onChange)" in switches)
        assertTrue("Switch(checked = on, onCheckedChange = null)" in switches)
    }
}
