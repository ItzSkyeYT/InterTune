/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.utils.FamilyChoice.Attempt
import com.dd3boh.outertune.utils.FamilyChoice.Memory
import com.dd3boh.outertune.utils.FamilyChoice.Network
import com.zionhuang.innertube.AddressPolicy
import com.zionhuang.innertube.IpFamily
import com.zionhuang.innertube.IpFamily.V4
import com.zionhuang.innertube.IpFamily.V6
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FamilyChoiceTest {
    private val wifi = Network(key = 100, v4 = true, v6 = true)
    private val otherWifi = Network(key = 200, v4 = true, v6 = true)
    private val v4Only = Network(key = 300, v4 = true, v6 = false)
    private val v6Only = Network(key = 400, v4 = false, v6 = true)
    private val now = 50_000_000L

    private fun ip(literal: String) = InetAddress.getByName(literal)

    @Test
    fun `a dual-stack network asks over IPv4 first`() {
        assertEquals(V4, FamilyChoice.first(wifi, null, now))
    }

    @Test
    fun `a single-family network is left to the system`() {
        assertNull(FamilyChoice.first(v4Only, null, now))
        assertNull(FamilyChoice.first(v6Only, null, now))
        assertNull(FamilyChoice.first(null, null, now))
    }

    @Test
    fun `the family that worked goes first, on that network and for a while`() {
        val memory = Memory(wifi.key, V6, workedAt = now)
        assertEquals(V6, FamilyChoice.first(wifi, memory, now + FamilyChoice.REMEMBER_MS - 1))
        assertEquals(V4, FamilyChoice.first(wifi, memory, now + FamilyChoice.REMEMBER_MS))
        assertEquals(V4, FamilyChoice.first(otherWifi, memory, now))
    }

    @Test
    fun `only the bot check on a dual-stack network earns a second family`() {
        assertEquals(V6, FamilyChoice.retryOver(wifi, V4, refused = true, memory = null, now = now))
        assertEquals(V4, FamilyChoice.retryOver(wifi, V6, refused = true, memory = null, now = now))
        assertNull(FamilyChoice.retryOver(wifi, V4, refused = false, memory = null, now = now))
        assertNull(FamilyChoice.retryOver(v6Only, null, refused = true, memory = null, now = now))
        assertNull(FamilyChoice.retryOver(null, V4, refused = true, memory = null, now = now))
    }

    @Test
    fun `after both families were refused the second is not asked again for a while`() {
        val memory = Memory(wifi.key, bothRefusedAt = now)
        val wait = FamilyChoice.RETRY_AFTER_BOTH_REFUSED_MS
        assertNull(FamilyChoice.retryOver(wifi, V4, true, memory, now + wait - 1))
        assertEquals(V6, FamilyChoice.retryOver(wifi, V4, true, memory, now + wait))
        // Another network has its own record.
        assertEquals(V6, FamilyChoice.retryOver(otherWifi, V4, true, memory, now))
    }

    @Test
    fun `both refused keeps what worked there before`() {
        val memory = Memory(wifi.key, V6, workedAt = now - 1000)
        val after = FamilyChoice.afterBothRefused(memory, wifi, now)
        assertEquals(V6, after.worked)
        assertEquals(now, after.bothRefusedAt)
        assertNull(FamilyChoice.afterBothRefused(memory, otherWifi, now).worked)
    }

    @Test
    fun `a network's families come from addresses that reach the internet`() {
        val dual = FamilyChoice.networkOf(1, listOf(ip("192.168.1.20"), ip("fe80::1"), ip("2001:db8:1::20")))
        assertTrue(dual.dualStack)

        // IPv6 off upstream: link-local and unique local only.
        val v4 = FamilyChoice.networkOf(1, listOf(ip("192.168.1.20"), ip("fe80::1"), ip("fd00::20")))
        assertTrue(v4.v4)
        assertFalse(v4.v6)

        // 464XLAT: the CLAT's own address does not make IPv4 native.
        val xlat = FamilyChoice.networkOf(1, listOf(ip("2001:db8:2::5"), ip("192.0.0.4")))
        assertFalse(xlat.v4)
        assertTrue(xlat.v6)
    }

    /** Runs [FamilyChoice.resolve] with canned answers per family, recording what was asked. */
    private class Asker(private val answers: Map<IpFamily?, Attempt<String>>) {
        val asked = mutableListOf<AddressPolicy?>()
        suspend fun ask(policy: AddressPolicy?): Attempt<String> {
            asked += policy
            return answers.getValue(policy?.first)
        }
    }

    private val served = Attempt("served", ok = true, refused = false)
    private val botCheck = Attempt("bot check", ok = false, refused = true)
    private val unavailable = Attempt("unavailable", ok = false, refused = false)

    @Test
    fun `IPv4 refused, IPv6 serves it, and IPv6 is remembered`() = runBlocking {
        val asker = Asker(mapOf(V4 to botCheck, V6 to served))
        val outcome = FamilyChoice.resolve(wifi, null, now, asker::ask)
        assertEquals("served", outcome.chosen.value)
        assertEquals(listOf(AddressPolicy(V4), AddressPolicy(V6, only = true)), asker.asked)
        assertEquals(Memory(wifi.key, V6, workedAt = now), outcome.memory)

        // The next song goes straight to IPv6, one try.
        val next = Asker(mapOf(V4 to botCheck, V6 to served))
        val again = FamilyChoice.resolve(wifi, outcome.memory, now + 60_000, next::ask)
        assertEquals("served", again.chosen.value)
        assertEquals(listOf(AddressPolicy(V6)), next.asked)
    }

    @Test
    fun `both refused answers with the first refusal, so the throttle still backs off`() = runBlocking {
        val asker = Asker(mapOf(V4 to botCheck, V6 to Attempt("refused too", ok = false, refused = true)))
        val outcome = FamilyChoice.resolve(wifi, null, now, asker::ask)
        assertEquals("bot check", outcome.chosen.value)
        assertTrue(outcome.chosen.refused)
        assertEquals(now, outcome.memory?.bothRefusedAt)

        // And the next song does not ask twice.
        val next = Asker(mapOf(V4 to botCheck, V6 to served))
        FamilyChoice.resolve(wifi, outcome.memory, now + 60_000, next::ask)
        assertEquals(listOf(AddressPolicy(V4)), next.asked)
    }

    @Test
    fun `a song that is simply unavailable is asked once`() = runBlocking {
        val asker = Asker(mapOf(V4 to unavailable, V6 to served))
        val outcome = FamilyChoice.resolve(wifi, null, now, asker::ask)
        assertEquals("unavailable", outcome.chosen.value)
        assertEquals(1, asker.asked.size)
        assertNull(outcome.memory)
    }

    @Test
    fun `a single-family network is asked once, the system's way`() = runBlocking {
        val asker = Asker(mapOf(null to botCheck))
        val outcome = FamilyChoice.resolve(v6Only, null, now, asker::ask)
        assertEquals(listOf<AddressPolicy?>(null), asker.asked)
        assertEquals("bot check", outcome.chosen.value)
    }

    @Test
    fun `a family that works refreshes its memory`() = runBlocking {
        val old = Memory(wifi.key, V6, workedAt = now - FamilyChoice.REMEMBER_MS + 1000)
        val asker = Asker(mapOf(V6 to served))
        val outcome = FamilyChoice.resolve(wifi, old, now, asker::ask)
        assertEquals(Memory(wifi.key, V6, workedAt = now), outcome.memory)
    }
}
