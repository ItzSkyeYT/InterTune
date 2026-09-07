package com.dd3boh.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The signature and the scrobble threshold, the two things here that fail silently.
 *
 * A wrong signature comes back only as "Invalid method signature", and a wrong threshold means
 * either nothing is ever scrobbled or everything is, both of which look like working software.
 */
class SignatureTest {

    private fun sign(api: LastFm, params: Map<String, String>): String {
        val m = LastFm::class.java.getDeclaredMethod("sign", Map::class.java)
        m.isAccessible = true
        return m.invoke(api, params) as String
    }

    @Test
    fun signatureSortsByNameAndAppendsTheSecret() {
        val actual = sign(LastFm("apikey", "secret"), mapOf("b" to "2", "a" to "1"))
        val expected = MessageDigest.getInstance("MD5")
            .digest("a1b2secret".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun signatureIsIndependentOfInsertionOrder() {
        val api = LastFm("k", "s")
        assertEquals(
            sign(api, linkedMapOf("track" to "x", "artist" to "y", "method" to "z")),
            sign(api, linkedMapOf("method" to "z", "artist" to "y", "track" to "x")),
        )
    }

    @Test
    fun thresholdFollowsLastFmsRule() {
        // Under thirty seconds never counts, whatever happens.
        assertFalse(LastFm.qualifies(playedMs = 29_000, durationSeconds = 29))
        // Half of a short song.
        assertFalse(LastFm.qualifies(playedMs = 59_000, durationSeconds = 120))
        assertTrue(LastFm.qualifies(playedMs = 60_000, durationSeconds = 120))
        // Four minutes caps it, so a long song does not need half.
        assertTrue(LastFm.qualifies(playedMs = 4 * 60_000, durationSeconds = 20 * 60))
        assertFalse(LastFm.qualifies(playedMs = 4 * 60_000 - 1, durationSeconds = 20 * 60))
    }
}
