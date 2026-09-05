/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

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
    const val POLLS_URL = "https://gist.githubusercontent.com/ItzSkyeYT/REPLACE_WITH_GIST_ID/raw/polls.json"

    /** Umami base, no trailing slash. Must be the tunnel hostname, not the machine behind it. */
    const val UMAMI_URL = "https://REPLACE_WITH_TUNNEL_HOSTNAME"

    /** Umami website id that poll answers are recorded against. */
    const val UMAMI_WEBSITE_ID = "REPLACE_WITH_WEBSITE_ID"

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
    val isConfigured: Boolean
        get() = !POLLS_URL.contains("REPLACE_WITH") &&
                !UMAMI_URL.contains("REPLACE_WITH") &&
                !UMAMI_WEBSITE_ID.contains("REPLACE_WITH")
}
