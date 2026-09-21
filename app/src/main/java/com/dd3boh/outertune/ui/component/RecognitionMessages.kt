/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.dd3boh.outertune.R
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The phrases the app says while it is listening, in one place.
 *
 * They were private to the listening sheet, which was fine while the sheet was the only way in.
 * The dedicated screen wants the same voice, and two copies of a joke is how one of them quietly
 * stops matching the other.
 */
/**
 * Shown while it listens, cycling so the screen never looks frozen on one sentence.
 *
 * Only true when there is a playlist to add to, which is why it is kept apart from the rest.
 * The dedicated screen adds nothing, and telling somebody it "will keep adding what it hears"
 * there is a promise it does not keep.
 */
internal val ADDING_MESSAGES = listOf(
    R.string.recognition_listening_hint,
)

internal val LISTENING_MESSAGES = listOf(
    R.string.recognition_msg_ears_open,
    R.string.recognition_msg_waiting_chorus,
    R.string.recognition_msg_leave_running,
    R.string.recognition_msg_closer_helps,
    R.string.recognition_msg_nodding,
    R.string.recognition_msg_eavesdrop,
    R.string.recognition_msg_hum,
)

/** Shown once it has something and is working out what. */
internal val IDENTIFYING_MESSAGES = listOf(
    R.string.recognition_identifying,
    R.string.recognition_msg_matching,
    R.string.recognition_msg_asking,
    R.string.recognition_msg_narrowing,
    R.string.recognition_msg_squiggles,
    R.string.recognition_msg_california,
    R.string.recognition_msg_thinking_hard,
)

/**
 * One in a thousand, and no more often than that.
 *
 * Rolled once per phrase rather than per frame, so it cannot flicker in and out while somebody is
 * reading it.
 */
internal const val SECRET_ODDS = 1000

private const val PHRASE_MS = 2_400L

/**
 * A phrase that changes every couple of seconds for as long as it is listening.
 *
 * Drawn at random rather than cycled in order, so two listens in a row do not read the same, and
 * never the phrase that is already on screen, which would look like it had stopped.
 */
@Composable
fun rememberRecognitionPhrase(
    listening: Boolean,
    identifying: Boolean,
    /** True when matches are being added to a playlist, which unlocks the phrases that say so. */
    adding: Boolean = false,
): State<String> {
    val pool = when {
        identifying -> IDENTIFYING_MESSAGES
        adding -> LISTENING_MESSAGES + ADDING_MESSAGES
        else -> LISTENING_MESSAGES
    }
    val secret = stringResource(R.string.recognition_msg_secret)
    val resolved = pool.map { stringResource(it) }
    val first = stringResource(R.string.recognition_msg_ears_open)

    val phrase = remember { mutableStateOf(first) }
    LaunchedEffect(listening, identifying) {
        if (!listening) {
            phrase.value = first
            return@LaunchedEffect
        }
        fun draw(): String =
            if (Random.nextInt(SECRET_ODDS) == 0) secret
            else resolved.filterNot { it == phrase.value }.randomOrNull() ?: resolved.first()

        phrase.value = draw()
        while (true) {
            delay(PHRASE_MS)
            phrase.value = draw()
        }
    }
    return phrase
}

/**
 * Three dots after the phrase, arriving one at a time and starting over.
 *
 * Two things at once, because either alone looks wrong. The count steps 1, 2, 3 so it reads as
 * something working rather than something pulsing, and each dot fades in as its turn comes rather
 * than appearing on a frame boundary. The full width of three is always reserved, so the phrase
 * beside them never shifts as they come and go.
 */
@Composable
fun AnimatedDots(
    color: Color = LocalContentColor.current,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)),
        label = "step",
    )

    Box {
        // The spacer. Transparent, and the same three characters, so the line is laid out once at
        // its widest and never re-measured.
        Text(text = "...", style = style, color = Color.Transparent)
        Row {
            for (i in 0 until 3) {
                // Fully on once the step has passed this dot, fading in across the step before it.
                val alpha = (step - i).coerceIn(0f, 1f)
                Text(
                    text = ".",
                    style = style,
                    color = color,
                    modifier = Modifier.alpha(alpha),
                )
            }
        }
    }
}
