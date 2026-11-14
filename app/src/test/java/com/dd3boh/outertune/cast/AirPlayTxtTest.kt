/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What an AirPlay speaker's TXT record says, and what the phone does with it. */
class AirPlayTxtTest {
    /** As a third-party AirPlay 2 speaker answers `_raop._tcp` (the keys OwnTone and pyatv read). */
    private val speaker = mapOf(
        "txtvers" to "1", "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,4", "md" to "0,1,2",
        "pw" to "false", "sf" to "0x4", "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP",
        "vn" to "65537", "vs" to "366.0", "am" to "Zeppelin Wireless",
    )

    @Test
    fun `a TXT record is read key by key`() {
        val txt = AirPlayTxt.parse(speaker)
        assertEquals(setOf(0, 4), txt.encryptions)
        assertEquals(setOf(0, 1, 2, 3), txt.codecs)
        assertEquals(setOf(0, 1, 2), txt.metadata)
        assertEquals(setOf("UDP"), txt.transports)
        assertFalse(txt.password)
        assertEquals(4L, txt.flags)
        assertEquals("Zeppelin Wireless", txt.model)
        assertEquals(2, txt.channels)
        assertEquals(44_100, txt.rate)
        assertEquals(16, txt.sampleSize)
        assertEquals(0x204L, AirPlayTxt.parse(mapOf("SF" to "0x204")).flags)
        assertEquals(516L, AirPlayTxt.parse(mapOf("sf" to "516")).flags)
    }

    @Test
    fun `a speaker that takes audio as it is gets Apple Lossless unencrypted, with auth-setup when it lists MFi-SAP`() {
        assertEquals(AirPlayPlan.Ok(AirPlayCodec.ALAC, AirPlayCrypto.CLEAR, authSetup = true), AirPlayTxt.parse(speaker).plan())
        assertEquals(AirPlayPlan.Ok(AirPlayCodec.ALAC, AirPlayCrypto.CLEAR, authSetup = false), AirPlayTxt.parse(speaker + ("et" to "0,3,5")).plan())
        // A record that says nothing is tried the classic way.
        assertEquals(AirPlayPlan.Ok(AirPlayCodec.ALAC, AirPlayCrypto.CLEAR, authSetup = false), AirPlayTxt().plan())
    }

    @Test
    fun `an AirPort Express that takes only RSA gets RSA, and one that takes only PCM gets L16`() {
        assertEquals(AirPlayCrypto.RSA, (AirPlayTxt.parse(mapOf("et" to "1")).plan() as AirPlayPlan.Ok).crypto)
        assertEquals(AirPlayCrypto.CLEAR, (AirPlayTxt.parse(mapOf("et" to "0,1")).plan() as AirPlayPlan.Ok).crypto)
        assertEquals(AirPlayCodec.PCM, (AirPlayTxt.parse(mapOf("cn" to "0,2")).plan() as AirPlayPlan.Ok).codec)
    }

    @Test
    fun `speakers that want a password, a code or TCP, or take only AAC, are refused with why`() {
        assertTrue(AirPlayTxt.parse(mapOf("pw" to "true")).plan() is AirPlayPlan.No)
        assertTrue(AirPlayTxt.parse(mapOf("sf" to "0x80")).plan() is AirPlayPlan.No)
        assertTrue(AirPlayTxt.parse(mapOf("sf" to "0x200")).plan() is AirPlayPlan.No)
        assertTrue(AirPlayTxt.parse(mapOf("sf" to "0x8")).plan() is AirPlayPlan.No)
        assertTrue(AirPlayTxt.parse(mapOf("tp" to "TCP")).plan() is AirPlayPlan.No)
        assertTrue(AirPlayTxt.parse(mapOf("tp" to "TCP,UDP")).plan() is AirPlayPlan.Ok)
        val aac = AirPlayTxt.parse(mapOf("cn" to "2,3")).plan()
        assertTrue(aac is AirPlayPlan.No && "AAC" in aac.reason)
    }

    @Test
    fun `HomePods, Apple TVs on tvOS and Macs are refused, the old Apple TVs and AirPort Express are not`() {
        for (model in listOf("AudioAccessory1,1", "AudioAccessory5,1", "AppleTV5,3", "AppleTV11,1", "AppleTV14,1", "MacBookPro18,3", "Mac14,2", "iMac21,1", "Macmini9,1")) {
            assertTrue(model, AirPlayTxt.parse(mapOf("am" to model)).plan() is AirPlayPlan.No)
        }
        for (model in listOf("AppleTV2,1", "AppleTV3,2", "AirPort10,115", "AirPort4,107", "Zeppelin Wireless", "WiiM Pro", "Denon AVR-X1600H")) {
            assertTrue(model, AirPlayTxt.parse(mapOf("am" to model)).plan() is AirPlayPlan.Ok)
        }
    }

    @Test
    fun `a speaker found by NSD is named after the at sign and known by its MAC`() {
        val device = AirPlayDevices.of("5C:AA:FD:01:02:03@Kitchen".replace(":", ""), "192.168.1.30", 7000, speaker)
        assertEquals(CastProtocol.AIRPLAY, device.protocol)
        assertEquals("airplay:5CAAFD010203", device.id)
        assertEquals("Kitchen", device.name)
        assertEquals("192.168.1.30", device.host)
        assertEquals(7000, device.port)
        assertEquals("Zeppelin Wireless", device.model)
        assertTrue(device.audioOnly)
        assertFalse(AirPlayDevices.of("AABB@Living room", "10.0.0.2", 7000, mapOf("am" to "AppleTV3,2")).audioOnly)
        assertEquals("NoAtSign", AirPlayDevices.of("NoAtSign", "10.0.0.2", 5000, emptyMap()).name)
    }

    @Test
    fun `the picker shows a model a person reads, not Apple's identifier`() {
        assertEquals("AirPort Express", AirPlayDevices.modelName("AirPort10,115"))
        assertEquals("Apple TV", AirPlayDevices.modelName("AppleTV3,2"))
        assertEquals("HomePod", AirPlayDevices.modelName("AudioAccessory5,1"))
        assertEquals("Mac", AirPlayDevices.modelName("MacBookPro18,3"))
        assertEquals("Zeppelin", AirPlayDevices.modelName("Zeppelin1,2"))
        assertEquals("Zeppelin Wireless", AirPlayDevices.modelName("Zeppelin Wireless"))
        assertEquals("Denon AVR-X1600H", AirPlayDevices.modelName(" Denon AVR-X1600H "))
        assertEquals(null, AirPlayDevices.modelName(""))
        assertEquals("AirPort Express", AirPlayDevices.of("AABB@Den", "10.0.0.2", 5000, mapOf("am" to "AirPort10,115")).model)
        // The plan still reads the identifier itself.
        val homePod = AirPlayDevices.of("AABB@Kitchen", "10.0.0.3", 7000, mapOf("am" to "AudioAccessory5,1"))
        assertEquals("HomePod", homePod.model)
        assertTrue(AirPlayDevices.txtOf(homePod).plan() is AirPlayPlan.No)
    }

    @Test
    fun `the TXT record rides in the location, so a remembered speaker keeps it`() {
        val device = AirPlayDevices.of("AABBCCDDEEFF@Den", "10.0.0.3", 5000, speaker + ("am" to "B&W Zeppelin=Wireless"))
        assertTrue(device.location!!.startsWith("raop:"))
        val again = RecentReceivers.decode(RecentReceivers.encode(listOf(device))).single()
        assertEquals(device, again)
        val txt = AirPlayDevices.txtOf(again)
        assertEquals(AirPlayTxt.parse(speaker + ("am" to "B&W Zeppelin=Wireless")), txt)
        assertEquals(AirPlayTxt(), AirPlayDevices.txtOf(CastDevice(CastProtocol.DLNA, "x", "x", "h", 1, location = "http://h/desc.xml")))
    }

    @Test
    fun `airplay and an address is a speaker typed in, on 5000 unless a port is given`() {
        val typed = CastAddress.parse("airplay 10.0.2.2")!!
        assertEquals(CastProtocol.AIRPLAY, typed.protocol)
        assertEquals("10.0.2.2", typed.host)
        assertEquals(5000, typed.port)
        assertEquals(7000, CastAddress.parse("AirPlay 10.0.2.2:7000")!!.port)
        assertNull(CastAddress.parse("airplay"))
        assertTrue(AirPlayTxt() == AirPlayDevices.txtOf(typed))
    }

    @Test
    fun `AirPlay speakers are listed beside the other receivers, by name`() {
        val air = AirPlayDevices.of("AABB@Zeppelin", "10.0.0.5", 7000, speaker)
        val dlna = CastDevice(CastProtocol.DLNA, "uuid:1", "Zeppelin", "10.0.0.5", 49152, location = "http://10.0.0.5:49152/d.xml")
        val tv = CastDevice(CastProtocol.CHROMECAST, "chromecast:1", "Attic TV", "10.0.0.9", 8009)
        val merged = CastDevices.merge(emptyList(), listOf(dlna), listOf(tv), listOf(air))
        assertEquals(listOf("Attic TV", "Zeppelin", "Zeppelin"), merged.map { it.name })
        assertEquals(setOf(CastProtocol.AIRPLAY, CastProtocol.DLNA), merged.drop(1).map { it.protocol }.toSet())
    }

    @Test
    fun `the flag is on in every build, a release included, and AirPlay leans on raw audio`() {
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        assertTrue(Regex("""\bval CAST_AIRPLAY = true\s*$""", RegexOption.MULTILINE).containsMatchIn(flags))
        // The link plays the cast server's raw audio, which needs the decoder CAST_PCM brings: one
        // must never be on in a build where the other is off.
        assertTrue(Regex("""\bval CAST_PCM = true\s*$""", RegexOption.MULTILINE).containsMatchIn(flags))
        assertEquals(listOf(AirPlay.RATE), (AirPlayLink.FORMATS.pcm.map { it.rate }))
    }

    @Test
    fun `discovery looks for AirPlay speakers only with the flag`() {
        val code = File("src/main/java/com/dd3boh/outertune/cast/CastDiscovery.kt").readText()
        assertTrue("if (Unreleased.CAST_AIRPLAY) listOf(CastProtocol.AIRPLAY to AirPlay.SERVICE_TYPE) else emptyList()" in code)
        assertTrue("CastProtocol.AIRPLAY -> if (Unreleased.CAST_AIRPLAY)" in code)
    }
}
