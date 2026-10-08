/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import androidx.media3.common.PlaybackException
import java.io.PrintStream
import java.io.PrintWriter

/**
 * What a failure says of itself, as the app may show, copy and log it: without the network
 * addresses it names.
 *
 * Android words a connection that failed as "failed to connect to music.youtube.com/203.0.113.57
 * (port 443) from /192.0.2.44 (port 51234) after 10000ms", and the second address is the phone's
 * own: libcore's IoBridge.createMessageForException asks the socket where it is bound and writes
 * that in. Over IPv6 it is a public address, which says whose line this is. OkHttp's ConnectPlan
 * puts "Failed to connect to music.youtube.com/203.0.113.57:443" around a refusal, Media3 takes
 * that line whole as the message of its own exception, and a stack trace prints every one of them.
 * The log, the player's error screen and the crash report are all pasted into issues and chats as
 * they are.
 *
 * The exception's class, the host's name, how long was waited, the reason and every frame stay.
 * An address becomes the word IPv4 or IPv6, because which of the two a connection failed over is
 * half of what such a line is read for, and a port goes altogether.
 *
 * An address is known by its shape and nothing else, so anything of that shape goes with it: four
 * numbers under 256 with dots between them, an app version such as 0.10.9.5 included. This is for
 * what a throwable says, not for the lines about the app and the phone that go above it.
 */
object ErrorText {
    private const val GROUP = """[0-9A-Fa-f]{1,4}"""
    private const val OCTET = """(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"""

    /**
     * A colon, as itself or as a url writes one in a parameter: %3A, and %253A where the url is a
     * parameter of something else. A stream url carries the address it was issued to that way,
     * and a failure to parse one quotes it.
     */
    private const val COLON = """(?::|%(?:25)?3[Aa])"""

    /**
     * Where an address may begin: not in the middle of a word or a number, though straight after
     * something a url has written with a %, as it writes the = or the / before one.
     */
    private const val START = """(?:(?<![0-9A-Za-z_.])|(?<=%[0-9A-Fa-f]{2}))"""

    /**
     * A port run on to an address. After an IPv6 address without brackets, which is how an older
     * Android writes the two, it reads as one more group, and goes as one where there is room.
     */
    private const val PORT = """(?:$COLON[0-9]{1,5})?"""

    /**
     * An IPv6 address: all eight groups, or fewer with a :: where zeros are left out. The end of
     * one may be written as an IPv4 address, and the interface it is on may follow a %. Two hex
     * digits after a % are a url's way of writing something else, and are left.
     *
     * Not :: alone. A socket that was never bound reports that, it is nobody's address, and it is
     * also how Kotlin writes a reference.
     */
    private const val V6 =
        """(?:$GROUP(?:$COLON$GROUP){7}|(?:$GROUP(?:$COLON$GROUP){0,6})?$COLON$COLON$GROUP(?:$COLON$GROUP){0,6}""" +
            """|$GROUP(?:$COLON$GROUP){0,6}$COLON$COLON)""" +
            """(?:(?:\.[0-9]{1,3}){3})?(?:%(?![0-9A-Fa-f]{2})[0-9A-Za-z_.\-]{1,20})?"""

    /** In brackets or not. */
    private val IPV6 = Regex("""(?:\[$V6\]|$START$V6)$PORT(?![0-9A-Za-z_])""")

    /** Four numbers and no more: 1.2.840.113549.1 is not an address with something after it. */
    private val IPV4 = Regex("""$START$OCTET(?:\.$OCTET){3}$PORT(?![0-9A-Za-z_]|\.[0-9])""")

    /** A port as the platform writes it, in words after the address. */
    private val PORT_IN_WORDS = Regex(""" \(port [0-9]{1,5}\)""")

    /**
     * [text] without the addresses and ports in it. The ports in words go first, so that nothing
     * is left that reads as an address only once they are gone: what has been through here comes
     * back as it is, and a text may be given twice.
     */
    fun withoutAddresses(text: String): String =
        text.replace(PORT_IN_WORDS, "").replace(IPV6, "IPv6").replace(IPV4, "IPv4")

    /** [failure] and its causes as printStackTrace writes them, without the addresses. */
    fun of(failure: Throwable): String = withoutAddresses(failure.stackTraceToString())

    /**
     * [failure] for a call to Log that is handed a throwable, as in Log.w(TAG, "...", failure):
     * what is written is [failure]'s trace without the addresses.
     *
     * Log asks the throwable it is given to print itself, and before that looks down its causes
     * for an UnknownHostException, which it gives no trace at all so that a phone that is only
     * offline does not fill the log. So this prints as [failure] would and keeps it as its cause,
     * and Log goes on deciding as it did, and splitting a long trace as it did.
     */
    fun forLog(failure: Throwable): Throwable = Printed(failure)

    private class Printed(private val failure: Throwable) : Throwable(null, failure) {
        override fun printStackTrace(s: PrintWriter) = s.print(of(failure))
        override fun printStackTrace(s: PrintStream) = s.print(of(failure))
        override fun toString(): String = withoutAddresses(failure.toString())
    }

    /**
     * The player's error in one line, for the error screen and for the report copied from it: what
     * the player calls it, its code, and the first reason given under it, or [unknown] when there
     * is none.
     */
    fun playerLine(error: PlaybackException, unknown: String = ""): String = withoutAddresses(
        "${error.message} (${error.errorCode}): ${error.cause?.message ?: error.cause?.cause?.message ?: unknown}"
    )
}
