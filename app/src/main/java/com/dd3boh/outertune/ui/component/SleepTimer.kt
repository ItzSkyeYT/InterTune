/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.SleepTimerDialog
import com.dd3boh.outertune.ui.component.rememberSleepTimerState
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Whether the sleep timer is running and roughly how long it has left, ticking once a second while
 * it runs. Shared by the player menu's grid entry and the button on the player screen.
 */
@Composable
fun rememberSleepTimerState(playerConnection: PlayerConnection): Pair<Boolean, Long> {
    val sleepTimerEnabled = remember(
        playerConnection.service.sleepTimer.triggerTime,
        playerConnection.service.sleepTimer.pauseWhenSongEnd
    ) {
        playerConnection.service.sleepTimer.isActive
    }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerEnabled) {
        if (sleepTimerEnabled) {
            while (isActive) {
                val newSleepTimerTimeLeft = if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                    playerConnection.player.duration - playerConnection.player.currentPosition
                } else {
                    playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                }
                delay(1000L)

                withContext(Dispatchers.Main) {
                    sleepTimerTimeLeft = newSleepTimerTimeLeft
                }
            }
        }
    }

    return sleepTimerEnabled to sleepTimerTimeLeft
}

/**
 * Picks a length and starts the sleep timer, or stops at the end of the song. Moved unchanged from
 * PlayerMenu so the player screen can open it too.
 */
@Composable
fun SleepTimerDialog(playerConnection: PlayerConnection, onDismiss: () -> Unit) {
    var sleepTimerValue by remember {
        mutableFloatStateOf(30f)
    }

    AlertDialog(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = { onDismiss() },
        icon = { Icon(imageVector = Icons.Rounded.Timer, contentDescription = null) },
        title = { Text(stringResource(R.string.sleep_timer)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDismiss() }
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            val focusRequester = remember {
                FocusRequester()
            }

            var showDialog by remember {
                mutableStateOf(false)
            }

            LaunchedEffect(showDialog) {
                if (showDialog) {
                    delay(300)
                    focusRequester.requestFocus()
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val pluralString = pluralStringResource(
                    R.plurals.minute,
                    sleepTimerValue.roundToInt(),
                    sleepTimerValue.roundToInt()
                )

                val endTime = System.currentTimeMillis() + (sleepTimerValue.roundToInt() * 60 * 1000).toLong()
                val calendarNow = Calendar.getInstance()
                val calendarEnd = Calendar.getInstance().apply { timeInMillis = endTime }

                // show date if it will span to next day
                val endTimeString =
                    if (calendarNow.get(Calendar.DAY_OF_YEAR) == calendarEnd.get(Calendar.DAY_OF_YEAR) &&
                        calendarNow.get(Calendar.YEAR) == calendarEnd.get(Calendar.YEAR)
                    ) {
                        SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, Locale.getDefault())
                            .format(Date(endTime))
                    } else {
                        SimpleDateFormat.getDateTimeInstance(
                            SimpleDateFormat.SHORT,
                            SimpleDateFormat.SHORT,
                            Locale.getDefault()
                        ).format(Date(endTime))
                    }

                Text(
                    text = "$pluralString\n$endTimeString",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                        .clip(shape = RoundedCornerShape(8.dp))
                        .clickable {
                            showDialog = true
                        }
                )

                // manual input
                if (showDialog) {
                    val initialText = TextFieldValue(
                        text = sleepTimerValue.roundToInt().toString(),
                        selection = TextRange(0, sleepTimerValue.roundToInt().toString().length),
                    )

                    val (textFieldValue, onTextFieldValueChange) = remember {
                        mutableStateOf(initialText)
                    }

                    TextField(
                        value = textFieldValue,
                        onValueChange = onTextFieldValueChange,
                        placeholder = { pluralString },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.MoreTime, null) },
                        colors = OutlinedTextFieldDefaults.colors(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = KeyboardType.Number
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val text = textFieldValue.text.toFloatOrNull()
                                if (text != null) {
                                    sleepTimerValue = textFieldValue.text.toFloatOrNull() ?: sleepTimerValue
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(weight = 1f, fill = false)
                            .focusRequester(focusRequester)
                    )
                }

                Slider(
                    value = sleepTimerValue,
                    onValueChange = { sleepTimerValue = it },
                    valueRange = 1f..120f,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Preset time options
                    val timeIntervals = listOf(15L, 30L, 45L, 60L)

                    // Create time chips for all intervals
                    val timeChips = timeIntervals.map { interval ->
                        val (timeString, duration) = getNextInterval(interval)
                        TimeChip(
                            duration = duration,
                            composable = {
                                OutlinedButton(
                                    onClick = { sleepTimerValue = duration },
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text(timeString)
                                }
                            }
                        )
                    }.sortedBy { it.duration } + remember {
                        TimeChip(
                            duration = Float.MAX_VALUE,
                            composable = {
                                OutlinedButton(
                                    onClick = {
                                        onDismiss()
                                        playerConnection.service.sleepTimer.start(-1)
                                    },
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text(stringResource(R.string.end_of_song))
                                }
                            }
                        )
                    }

                    timeChips.forEach { timeChip ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                        ) {
                            timeChip.composable()
                        }
                    }
                }
            }
        }
    )
}

data class TimeChip(
    val duration: Float,
    val composable: @Composable () -> Unit
) : Comparable<TimeChip> {
    override fun compareTo(other: TimeChip): Int {
        return duration.compareTo(other.duration)
    }
}

fun getNextInterval(targetMin: Long): Pair<String, Float> {
    require(targetMin in 1..60) { "Interval must be between 1 and 60 minutes" }

    val now = LocalDateTime.now()
    val intervalMinutes = targetMin - now.minute

    val targetTime: LocalDateTime = if (intervalMinutes > 0) {
        // Within this hour
        now.plusMinutes(intervalMinutes)
    } else if (intervalMinutes < 0) {
        // Next hour
        now.plusHours(1).plusMinutes(targetMin - now.minute)
//        now.plusMinutes((60 - now.minute) + targetMin)        // other way to calculate targetTime
    } else {
        // Equal to 0
        now.plusHours(1)
    }

    // Format the time
    val timeString = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT, Locale.getDefault())
        .format(Date(targetTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))

    // Calculate minutes between now and target
    val minutesBetween = ChronoUnit.MINUTES.between(now, targetTime).toFloat()

    return Pair(timeString, minutesBetween)
}
