/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listenbrainz

import com.dd3boh.listenbrainz.ListenBrainzException

/**
 * Where the account stands with ListenBrainz after its answer to listens, as the card in Account
 * and sync says it. Only a token ListenBrainz does not know asks for a new one ("no longer accepts
 * your token"). An account it takes no listens from says why and keeps its token, since pasting
 * another changes nothing: on 27 Nov 2026 a fresh token met the old message again, for an account
 * with no verified email. An answer of the moment (no connection, asked to wait, a server in
 * trouble, one listen refused) says nothing about either, and the plays wait.
 */
enum class ListenBrainzStanding {
    /** Listens taken: an account refused before takes them again. */
    TAKEN,

    /** ListenBrainz does not know the token: nothing goes until a new one is pasted. */
    TOKEN_REFUSED,

    /** The token works; the account needs a verified email before ListenBrainz takes its listens. */
    NEEDS_EMAIL,

    /** The token works; the account takes no listens for another reason (paused). */
    ACCOUNT_REFUSED,
    ;

    companion object {
        /** What [result], ListenBrainz's answer to listens, says; null when it says nothing new. */
        fun after(result: Result<*>): ListenBrainzStanding? = when (val e = result.exceptionOrNull()) {
            null -> TAKEN
            is ListenBrainzException.TokenRefused -> TOKEN_REFUSED
            is ListenBrainzException.AccountRefused -> if (e.needsEmail) NEEDS_EMAIL else ACCOUNT_REFUSED
            else -> null
        }
    }
}
