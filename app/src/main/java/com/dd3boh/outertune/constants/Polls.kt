/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import com.dd3boh.outertune.BuildConfig

/**
 * Where polls come from and where answers go.
 *
 * Both of these end up in a public APK and there is no way around that: anything the app connects
 * to can be read out of the file or watched on the wire. So neither may ever be a home address.
 *
 * Polls are READ from a gist. The maintainer's own machine pushes to it with a token that stays on
 * that machine, so the app never learns anything about where the polls were written. It also means
 * polls keep serving from GitHub's CDN when that machine is off.
 *
 * Answers are WRITTEN to Umami, which has to be reachable to accept them. That hostname must
 * therefore resolve to something in front of the real server, a tunnel or a proxy, never to a home
 * connection.
 */
object Polls {
    /**
     * Raw gist URL holding the poll list.
     *
     * Use the revision-less raw URL (gist.githubusercontent.com/<user>/<id>/raw/polls.json) so that
     * editing the gist is picked up without changing anything here.
     */
    val POLLS_URL: String by lazy { reveal(BuildConfig.POLLS_URL) }

    /**
     * Umami base, no trailing slash.
     *
     * Umami Cloud, which sidesteps the problem this comment used to warn about: nothing here
     * resolves to the maintainer's own machine, so the address in the apk gives nobody anything.
     * If this ever moves to a self-hosted instance it must point at a tunnel, never a home
     * connection.
     *
     * Note the region. Umami Cloud's dashboard lives at cloud.umami.is for every account, but the
     * ingest endpoint is regional, and an EU account only accepts events at eu.umami.is. Posting
     * to cloud.umami.is does not fail loudly, it fails to connect at all, so taking the dashboard
     * address at face value would have meant answers silently going nowhere forever.
     */
    val UMAMI_URL: String by lazy { reveal(BuildConfig.POLLS_UMAMI_URL) }

    /** Umami website id that poll answers are recorded against. */
    val UMAMI_WEBSITE_ID: String by lazy { reveal(BuildConfig.POLLS_UMAMI_WEBSITE_ID) }

    /**
     * Sent as the Umami `hostname`, which is a required field.
     *
     * A fixed string rather than anything from the device, because it is only there to satisfy the
     * schema and to keep app answers separate from any real website on the same instance.
     */
    const val UMAMI_HOSTNAME = "app.intertune"

    /** Umami event name. Everything else about the answer rides in the event's data object. */
    const val UMAMI_EVENT = "poll_answer"

    /** True once the placeholders above have been filled in. Nothing runs until they are. */
    /**
     * Undoes the build-time obfuscation. Same scheme and the same caveat as the Last.fm
     * credentials: this defeats a plain strings sweep of the apk and nothing more.
     */
    private fun reveal(masked: IntArray): String {
        if (masked.isEmpty()) return ""
        val mask = "InterTune".toByteArray()
        return String(
            ByteArray(masked.size) { i -> (masked[i] xor mask[i % mask.size].toInt()).toByte() }
        )
    }

    /** True once local.properties supplied all three. Nothing runs until it has. */
    val isConfigured: Boolean
        get() = POLLS_URL.isNotEmpty() && UMAMI_URL.isNotEmpty() && UMAMI_WEBSITE_ID.isNotEmpty()
}
