/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A time of day as the phone writes times: 23:00, or 11:00 PM where it counts twelve hours. A time
 * on the clock face and not a moment, so no day and no time zone come into it, and 02:30 is never
 * moved to 03:30 as it would be on the night the clocks go forward.
 */
fun formatTimeOfDay(context: Context, minuteOfDay: Int): String {
    val locale = context.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    return LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60).format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** The height a window needs for the clock face with its dialog, title and buttons. */
private const val CLOCK_FACE_ROOM_DP = 560

/** How far apart the times in the list are, where the window is too short for the clock face. */
private const val LIST_STEP_MINUTES = 15

/**
 * Picks a time of day, in minutes after midnight.
 *
 * Where the window has the height for it, the clock face, with the way to the keyboard beside the
 * buttons. Where it has not, on a phone on its side or in a short window, the times of the day a
 * quarter of an hour apart, in a list that opens on the one chosen. Not the fields to type in
 * there: their keyboard leaves a phone on its side so little height that the dialog's buttons went
 * under it, and the clock face does not fit at all.
 */
@Composable
fun TimeOfDayDialog(title: String, minuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    if (LocalConfiguration.current.screenHeightDp >= CLOCK_FACE_ROOM_DP) {
        ClockFaceDialog(title, minuteOfDay, onDismiss, onConfirm)
    } else {
        TimeListDialog(minuteOfDay, onDismiss, onConfirm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockFaceDialog(title: String, minuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minuteOfDay / 60 % 24, initialMinute = minuteOfDay % 60)
    var typing by rememberSaveable { mutableStateOf(false) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(title) },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(android.R.string.ok)) }
        },
        buttonsStart = {
            // Material's own button for this (TimePickerDialogDefaults.DisplayModeToggle, 1.4.0)
            // tells a screen reader the opposite of what it does: "Switch to clock mode" under the
            // keyboard's picture, while the clock face is showing.
            IconButton(onClick = { typing = !typing }) {
                Icon(
                    imageVector = if (typing) Icons.Rounded.Schedule else Icons.Rounded.Keyboard,
                    contentDescription = stringResource(if (typing) R.string.time_of_day_clock_face else R.string.time_of_day_type),
                )
            }
        },
    ) {
        if (!typing) {
            TimePicker(state = state)
        } else {
            // The fields scroll where the keyboard leaves little height, and the buttons stay:
            // DefaultDialog gives its content what the buttons leave.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TimeInput(state = state)
            }
        }
    }
}

@Composable
private fun TimeListDialog(minuteOfDay: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val context = LocalContext.current
    // The time chosen is in the list even when it is not on a quarter of an hour.
    val times = remember(minuteOfDay) { ((0 until 24 * 60 step LIST_STEP_MINUTES) + minuteOfDay).distinct().sorted() }
    // It opens with the time chosen third from the top, so that the ones just before it show too.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (times.indexOf(minuteOfDay) - 2).coerceAtLeast(0))

    ListDialog(onDismiss = onDismiss, state = state) {
        items(times) { minute ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfirm(minute) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                RadioButton(selected = minute == minuteOfDay, onClick = null)
                Text(
                    text = formatTimeOfDay(context, minute),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
