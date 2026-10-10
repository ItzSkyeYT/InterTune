/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** Reading what a web client's address needs solved, and putting the answers back. Every address here is made up. */
class StreamCipherTest {
    private val host = "https://rr1---sn-test.googlevideo.com/videoplayback"

    private fun cipher(vararg parts: Pair<String, String>) = parts.joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, "UTF-8") }

    @Test
    fun `a plain address with nothing to solve is read as it is and comes back as it was`() {
        val url = "$host?expire=1791548840&itag=251&mime=audio%2Fwebm"
        val address = StreamCipher.read(url, null)!!
        assertNull(address.signature)
        assertNull(address.n)
        assertFalse(address.needsSolving)
        assertEquals(url, StreamCipher.solved(address, null, null))
    }

    @Test
    fun `the n of a plain address is read, and only it is replaced`() {
        val url = "$host?expire=1791548840&n=AbC_dEf-123&itag=251&mime=audio%2Fwebm"
        val address = StreamCipher.read(url, null)!!
        assertEquals("AbC_dEf-123", address.n)
        assertTrue(address.needsSolving)
        assertEquals("$host?expire=1791548840&n=zYx987&itag=251&mime=audio%2Fwebm", StreamCipher.solved(address, null, "zYx987"))
    }

    @Test
    fun `a parameter that only ends in n is not the n`() {
        val address = StreamCipher.read("$host?mn=sn-test&pcm2cms=yes&itag=251", null)!!
        assertNull(address.n)
    }

    @Test
    fun `a cipher is read into its signature, the name the signature goes under, and its address`() {
        val inner = "$host?expire=1791548840&n=AbC_dEf-123&itag=251"
        val address = StreamCipher.read(null, cipher("s" to "AOq0QJ8wRgIh==", "sp" to "sig", "url" to inner))!!
        assertEquals("AOq0QJ8wRgIh==", address.signature)
        assertEquals("sig", address.signatureName)
        assertEquals(inner, address.url)
        assertEquals("AbC_dEf-123", address.n)
        assertTrue(address.needsSolving)
    }

    @Test
    fun `a cipher that names no parameter puts the signature under signature, as yt-dlp does`() {
        val address = StreamCipher.read(null, cipher("s" to "AOq0QJ8wRgIh==", "url" to "$host?itag=251"))!!
        assertEquals("signature", address.signatureName)
    }

    @Test
    fun `the solved signature and n are put back, written the way an address writes them`() {
        val address = StreamCipher.read(null, cipher("s" to "AOq0QJ8wRgIh==", "sp" to "sig", "url" to "$host?expire=1&n=AbC_dEf-123&itag=251"))!!
        assertEquals(
            "$host?expire=1&n=zYx987&itag=251&sig=hIgRw8JQ0qOA%3D%3D",
            StreamCipher.solved(address, signature = "hIgRw8JQ0qOA==", n = "zYx987"),
        )
    }

    @Test
    fun `a url beside a cipher wins, since nothing needs undoing then`() {
        val address = StreamCipher.read("$host?itag=251", cipher("s" to "AOq0", "url" to "$host?itag=140"))!!
        assertEquals("$host?itag=251", address.url)
        assertNull(address.signature)
    }

    @Test
    fun `a format with neither, or a cipher missing its signature or its address, has no address`() {
        assertNull(StreamCipher.read(null, null))
        assertNull(StreamCipher.read(null, ""))
        assertNull(StreamCipher.read(null, cipher("sp" to "sig", "url" to "$host?itag=251")))
        assertNull(StreamCipher.read(null, cipher("s" to "AOq0", "sp" to "sig")))
        assertNull(StreamCipher.read(null, "s=%ZZ&url=%ZZ"))
    }

    @Test
    fun `without an answer for something that needed one there is no address`() {
        val address = StreamCipher.read(null, cipher("s" to "AOq0QJ8wRgIh==", "sp" to "sig", "url" to "$host?n=AbC_dEf-123&itag=251"))!!
        assertNull(StreamCipher.solved(address, signature = null, n = "zYx987"))
        assertNull(StreamCipher.solved(address, signature = "hIgRw8JQ0qOA==", n = null))
    }

    @Test
    fun `an answer that is the question, or is not a value at all, is no answer`() {
        val address = StreamCipher.read("$host?n=AbC_dEf-123&itag=251", null)!!
        // What NewPipeExtractor's n function did on 9 Oct 2026: it handed the value back.
        assertNull(StreamCipher.solved(address, null, "AbC_dEf-123"))
        assertNull(StreamCipher.solved(address, null, ""))
        assertNull(StreamCipher.solved(address, null, "TypeError: x is not a function\n    at <anonymous>"))
        assertNull(StreamCipher.solved(address, null, "a".repeat(5000)))
    }

    @Test
    fun `an address that is not one of YouTube's stream hosts is dropped, whatever was solved`() {
        for (url in listOf(
            "https://example.com/videoplayback?n=AbC&itag=251",
            "https://googlevideo.com.example.com/videoplayback?n=AbC&itag=251",
            "http://rr1---sn-test.googlevideo.com/videoplayback?n=AbC&itag=251",
            "https://user@rr1---sn-test.googlevideo.com@example.com/videoplayback?n=AbC",
            "not an address",
        )) {
            val address = StreamCipher.read(url, null)
            assertNull(url, address?.let { StreamCipher.solved(it, null, "zYx987") })
        }
    }

    @Test
    fun `a po token goes on the end of an address, and none leaves it alone`() {
        assertEquals("$host?itag=251&pot=TOKEN", StreamCipher.withPot("$host?itag=251", "TOKEN"))
        assertEquals("$host?itag=251", StreamCipher.withPot("$host?itag=251", null))
        assertEquals("$host?itag=251", StreamCipher.withPot("$host?itag=251", ""))
        assertEquals("$host?pot=TOKEN", StreamCipher.withPot(host, "TOKEN"))
    }

    @Test
    fun `a po token is written as a value in an address has to be`() {
        // The two alphabets a token comes in, with the padding either can end in.
        assertEquals("$host?itag=251&pot=Mn_a-B.c~9%3D%3D", StreamCipher.withPot("$host?itag=251", "Mn_a-B.c~9=="))
        assertEquals("$host?itag=251&pot=a%2Bb%2Fc%3D", StreamCipher.withPot("$host?itag=251", "a+b/c="))
        assertEquals("a space is never a plus", "$host?itag=251&pot=a%20b", StreamCipher.withPot("$host?itag=251", "a b"))
        assertEquals("before a fragment, not after it", "$host?itag=251&pot=TOKEN#t=10", StreamCipher.withPot("$host?itag=251#t=10", "TOKEN"))
    }

    @Test
    fun `a play's name goes on after the token, and is sixteen characters an address can carry as they are`() {
        val named = StreamCipher.withCpn(StreamCipher.withPot("$host?itag=251", "TOKEN"), "abcDEF012345-_xy")
        assertEquals("$host?itag=251&pot=TOKEN&cpn=abcDEF012345-_xy", named)
        assertEquals("$host?itag=251", StreamCipher.withCpn("$host?itag=251", null))
        val made = List(50) { StreamCipher.newCpn() }
        assertTrue(made.all { Regex("""[A-Za-z0-9_-]{16}""").matches(it) })
        assertTrue("made anew for every play", made.distinct().size > 40)
    }
}
