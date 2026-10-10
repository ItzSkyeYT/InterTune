/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils.cipher

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a web client's stream address needs before it can be fetched, read out of a format and put
 * back once it is solved. Kept apart from whatever does the solving, so it can be tested with no
 * player script and no network.
 *
 * A client YouTube takes for an app (VISIONOS, IOS, ANDROID) is given an address that works as it
 * stands. A web client's is held back in two ways, both undone by functions in YouTube's player
 * script:
 * - the signature. The format has no url but a signatureCipher, a query string of its own with
 *   the address (url), a scrambled signature (s) and the name the unscrambled one goes under (sp);
 * - n. A parameter of the address itself, to be passed through a function of the script. Left as
 *   it was issued, the stream is refused.
 *
 * Solving the two is [ChallengeSolver]'s. This reads what is to be solved and builds the address
 * from the answers. It refuses an answer that cannot be one, and an address that is not a stream
 * host's: what comes back from the solver is what a script YouTube wrote made of a value YouTube
 * sent, so it is checked like anything else that arrives from outside.
 */
object StreamCipher {
    /** A format's address and what of it is still to solve. [signature] and [n] are as issued, decoded. */
    class Address(val url: String, val signature: String?, val signatureName: String, val n: String?) {
        val needsSolving: Boolean get() = signature != null || n != null
    }

    /** What yt-dlp takes when a cipher does not name the parameter. */
    private const val DEFAULT_SIGNATURE_NAME = "signature"

    private const val STREAM_HOST_SUFFIX = ".googlevideo.com"

    /** Far above any signature or n seen (about a hundred characters and about twenty). */
    private const val MAX_ANSWER_LENGTH = 2048

    private val PARAMETER_NAME = Regex("""[A-Za-z0-9_]{1,32}""")
    private val HOST = Regex("""[a-z0-9.-]{1,253}""")

    /**
     * What [url] says or, without one, [signatureCipher]. Null is a format with no address in it
     * at all, which is what a client that only streams over SABR gives, or a cipher that cannot
     * be read.
     */
    fun read(url: String?, signatureCipher: String?): Address? {
        if (!url.isNullOrBlank()) return Address(url, null, DEFAULT_SIGNATURE_NAME, nOf(url))
        if (signatureCipher.isNullOrBlank()) return null
        val fields = mutableMapOf<String, String>()
        for (field in signatureCipher.split('&')) {
            val name = field.substringBefore('=', "")
            if (name.isEmpty()) continue
            fields[name] = decoded(field.substringAfter('=', "")) ?: return null
        }
        val inner = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
        val signature = fields["s"]?.takeIf { it.isNotBlank() } ?: return null
        val name = fields["sp"] ?: DEFAULT_SIGNATURE_NAME
        if (!PARAMETER_NAME.matches(name)) return null
        return Address(inner, signature, name, nOf(inner))
    }

    /**
     * [address] with the solved [signature] and [n] in it, or null when it is not to be fetched:
     * an answer is missing for something that needed one, an answer cannot be one, or the address
     * is not on a stream host of YouTube's.
     */
    fun solved(address: Address, signature: String?, n: String?): String? {
        if (!isStreamHost(address.url)) return null
        var url = address.url
        if (address.n != null) {
            val answer = n?.takeIf { isAnswer(it, address.n) } ?: return null
            url = withParameter(url, "n", answer)
        }
        if (address.signature != null) {
            val answer = signature?.takeIf { isAnswer(it, address.signature) } ?: return null
            url = withParameter(url, address.signatureName, answer)
        }
        return url
    }

    /**
     * [url] with a po token on it, or as it is without one.
     *
     * Encoded as a value in an address has to be. The player used to append the token as it came,
     * and a token is base64 that can end in "=" and, in the alphabet some makers use, hold "+" and
     * "/". The apps that play from a web client all encode it (InnerTubeX's withPoToken).
     */
    fun withPot(url: String, pot: String?): String =
        if (pot.isNullOrEmpty()) url else appended(url, "pot", pot)

    /**
     * [url] with the play's own name on it, the cpn: sixteen characters the page makes up for
     * every play and puts on each fetch of the stream, the same one throughout.
     */
    fun withCpn(url: String, cpn: String?): String =
        if (cpn.isNullOrEmpty()) url else appended(url, "cpn", cpn)

    /** A cpn as YouTube.registerPlayback makes one for the play it reports. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    /** At the end of the query and before a fragment, as [withParameter] writes one that was not there. */
    private fun appended(url: String, name: String, value: String): String {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val fragment = if (hash < 0) "" else url.substring(hash)
        return beforeFragment + (if ('?' in beforeFragment) "&" else "?") + name + "=" + queryValue(value) + fragment
    }

    /** Everything but letters, digits and "-._~" as a percent and two hex digits: a space is %20 here, never "+". */
    private fun queryValue(value: String): String = buildString(value.length) {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val kept = code in '0'.code..'9'.code || code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code ||
                code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
            if (kept) append(code.toChar()) else append('%').append("0123456789ABCDEF"[code ushr 4]).append("0123456789ABCDEF"[code and 15])
        }
    }

    /**
     * Whether [value] can be the solved form of [question]. The same value back is a function
     * that did nothing, which is what NewPipeExtractor's did to n on 9 Oct 2026, and anything
     * with a space or a line break in it is an error message and not a value.
     */
    private fun isAnswer(value: String, question: String): Boolean =
        value.isNotEmpty() && value != question && value.length <= MAX_ANSWER_LENGTH &&
            value.all { it.code in 0x21..0x7E }

    /** Https, no user part, and a host under googlevideo.com: read by hand, as StreamCheck.urlForLog reads one. */
    private fun isStreamHost(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val authority = url.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return false
        val host = authority.substringBefore(':').lowercase()
        return HOST.matches(host) && host.endsWith(STREAM_HOST_SUFFIX)
    }

    private fun nOf(url: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == "n" }
            ?.let { decoded(it.substringAfter('=', "")) }
            ?.takeIf { it.isNotEmpty() }

    /** [url] with [name] set to [value]: in the place it had, or at the end when it had none. */
    private fun withParameter(url: String, name: String, value: String): String {
        val hash = url.indexOf('#')
        val beforeFragment = if (hash < 0) url else url.substring(0, hash)
        val fragment = if (hash < 0) "" else url.substring(hash)
        val mark = beforeFragment.indexOf('?')
        val path = if (mark < 0) beforeFragment else beforeFragment.substring(0, mark)
        val fields = if (mark < 0) emptyList() else beforeFragment.substring(mark + 1).split('&').filter { it.isNotEmpty() }
        val written = name + "=" + URLEncoder.encode(value, "UTF-8")
        var placed = false
        val kept = fields.mapNotNull { field ->
            when {
                field.substringBefore('=') != name -> field
                placed -> null
                else -> written.also { placed = true }
            }
        }
        return path + "?" + (if (placed) kept else kept + written).joinToString("&") + fragment
    }

    private fun decoded(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}
