/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.constants

import com.dd3boh.outertune.utils.BuiltInKeys

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
 * Answers are WRITTEN to InterTune's own counter, which has to be reachable to accept them. Never point
 * it at a home connection.
 */
object Polls {
    /**
     * Raw gist URL holding the poll list.
     *
     * Use the revision-less raw URL (gist.githubusercontent.com/<user>/<id>/raw/polls.json) so that
     * editing the gist is picked up without changing anything here.
     */
    val POLLS_URL: String get() = BuiltInKeys.pollsUrl

    /**
     * InterTune's own counter, no trailing slash: a Cloudflare worker (intertune-count) that takes
     * Umami's message shape at /api/send. The count moved there on 14 Nov 2026, when Umami Cloud's
     * free plan ran out. Nothing here resolves to the maintainer's own machine.
     */
    val COUNT_URL: String get() = BuiltInKeys.countUrl

    /** Umami website id that poll answers are recorded against. */
    val UMAMI_WEBSITE_ID: String get() = BuiltInKeys.umamiWebsiteId

    /**
     * Sent as the Umami `hostname`, which is a required field.
     *
     * A fixed string rather than anything from the device, because it is only there to satisfy the
     * schema and to keep app answers separate from any real website on the same instance.
     */
    const val UMAMI_HOSTNAME = "app.intertune"

    /** Umami event name. Everything else about the answer rides in the event's data object. */
    /** The longest text sent in one answer, the words of an "Other" choice included. */
    const val MAX_TEXT_CHARS = 400

    const val UMAMI_EVENT = "poll_answer"

    /**
     * Sent once per poll or announcement per install, the first time it is opened, so the
     * dashboard can say how many saw a question beside how many answered it.
     */
    const val UMAMI_VIEW_EVENT = "poll_view"

    /** True once the placeholders above have been filled in. Nothing runs until they are. */
    /**
     * True once the build supplied all three and this is the app signed with the release key (see
     * BuiltInKeys). Nothing runs until then, so a clone does not post its answers into these polls.
     */
    val isConfigured: Boolean
        get() = POLLS_URL.isNotEmpty() && COUNT_URL.isNotEmpty() && UMAMI_WEBSITE_ID.isNotEmpty()
}
