/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SleepScheduleKey
import com.dd3boh.outertune.constants.SleepScheduleStartKey
import com.dd3boh.outertune.constants.SleepTimerDefaults
import com.dd3boh.outertune.constants.SleepTimerFadeDurationKey
import com.dd3boh.outertune.constants.SleepTimerFadeKey
import com.dd3boh.outertune.constants.SleepTimerSongFinishKey
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.ScheduledSleep
import com.dd3boh.outertune.playback.SleepEnd
import com.dd3boh.outertune.playback.SleepTimer
import com.dd3boh.outertune.ui.dialog.TimeOfDayDialog
import com.dd3boh.outertune.ui.dialog.formatTimeOfDay
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** About the width of a space in the sheet's body text. */
private val SPACE_WIDTH = 4.dp

/**
 * What the sleep timer says about itself, once a second while it runs, or null while none does.
 * Shared by the player menu's grid entry, the button on the player screen and the sheet.
 */
@Composable
fun rememberSleepTimerStatus(playerConnection: PlayerConnection): SleepTimer.Status? {
    val timer = playerConnection.service.sleepTimer
    // Compose state, read here: the effect starts and stops with the timer, and starts again when
    // its time or its end changes, so a tap on +5 min shows at once and not at the next second.
    val active = timer.isActive
    val trigger = timer.triggerTime
    val end = timer.endOf

    var status by remember { mutableStateOf<SleepTimer.Status?>(null) }
    LaunchedEffect(active, trigger, end) {
        if (!active) {
            status = null
            return@LaunchedEffect
        }
        while (isActive) {
            status = timer.status()
            delay(1000L)
        }
    }
    return if (active) status ?: timer.status() else null
}

/**
 * Whether the sleep timer is running and roughly how long it has left: for the menu's entry and the
 * player's button, which show the time. Zero where it cannot be told, as for a song of unknown length.
 */
@Composable
fun rememberSleepTimerState(playerConnection: PlayerConnection): Pair<Boolean, Long> {
    val status = rememberSleepTimerStatus(playerConnection)
    return (status != null) to (status?.leftMs ?: 0L)
}

/**
 * Keeps a typed sleep timer length between 1 minute and 24 hours. There is no 120 minute cap
 * like the slider's, because this field exists to go past the slider's 2 hours.
 *
 * 0 and -1 are the dangerous values: SleepTimer.start(0) pauses playback at once, and -1 is its
 * "stop at the end of the song" value. A hardware keyboard can also type "NaN" or "Infinity",
 * which toFloatOrNull() accepts; NaN would pass coerceIn unchanged and make roundToInt() throw,
 * so non-finite values become 1. The 1440 minute cap keeps minutes * 60 * 1000 inside an Int.
 */
fun coerceSleepTimerMinutes(value: Float): Float {
    if (!value.isFinite()) return 1f
    return value.coerceIn(1f, 1440f)
}

/**
 * Sets the sleep timer, or, while one runs, shows it: opened from the player menu and from the
 * player screen. It is the same sheet in both states.
 *
 * Setting one: how long (a slider, the lengths people use, the clock times), or the end of the
 * song, the album or the queue, which starts at once as the end of the song always did. Running:
 * the time left, when it stops, and the same chips to move it. The switches are the same in both,
 * and are the settings themselves: the fade is the one in Player and audio, the night is the sleep
 * timer on a schedule, and they take effect as they are flipped, on a running timer too.
 *
 * A sheet that comes up from the bottom, so it is closed by dragging it down as a menu is, and on
 * a phone on its side it is no wider than a menu is there. It was a dialog across the whole
 * window: on its side that was a slider the length of the phone, and the row of times under it
 * was cut off where the window ran out of height. What does not fit now scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(playerConnection: PlayerConnection, onDismiss: () -> Unit) {
    var sleepTimerValue by remember {
        mutableFloatStateOf(30f)
    }
    val landscape = LocalLandscape.current
    val timer = playerConnection.service.sleepTimer
    val status = rememberSleepTimerStatus(playerConnection)

    ModalBottomSheet(
        onDismissRequest = { onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = landscape.panelWidth(BottomSheetDefaults.SheetMaxWidth),
        // In the middle of a wide window only the top and the bottom of the screen concern it: see BottomSheetMenu.
        contentWindowInsets = {
            if (landscape.active) WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical) else WindowInsets.safeDrawing
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
        ) {
            // The icon where there is height to spare. On its side the window has room for the rest and little more.
            if (!landscape.active) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            Text(
                text = stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (status == null) {
                SetTimer(
                    value = sleepTimerValue,
                    onValueChange = { sleepTimerValue = it },
                    onEnd = { end ->
                        onDismiss()
                        timer.start(end)
                    },
                )
            } else {
                RunningTimer(status = status, timer = timer, onEndOfSong = {
                    onDismiss()
                    timer.start(SleepEnd.SONG)
                })
            }

            SleepSwitches(letFinishApplies = status?.end == null)

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                if (status == null) {
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onDismiss()
                            timer.start(sleepTimerValue.roundToInt())
                        }
                    ) {
                        Text(stringResource(R.string.sleep_start))
                    }
                } else {
                    TextButton(
                        onClick = {
                            timer.clear()
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.sleep_stop))
                    }
                    TextButton(onClick = { onDismiss() }) {
                        Text(stringResource(R.string.sleep_close))
                    }
                }
            }
        }
    }
}

/** The length, as a slider with its answer above it, and the rows of ways to pick one: In, At and End of. */
@Composable
private fun SetTimer(value: Float, onValueChange: (Float) -> Unit, onEnd: (SleepEnd) -> Unit) {
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
        val minutes = value.roundToInt()
        val pluralString = pluralStringResource(R.plurals.minute, minutes, minutes)

        val endTime = System.currentTimeMillis() + (minutes * 60 * 1000).toLong()
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
                text = minutes.toString(),
                selection = TextRange(0, minutes.toString().length),
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
                        textFieldValue.text.toFloatOrNull()?.let {
                            onValueChange(coerceSleepTimerMinutes(it))
                        }
                    }
                ),
                modifier = Modifier.focusRequester(focusRequester)
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 1f..120f,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // In: how long from now. The lengths the leading players offer, and 90.
        ChipRow(stringResource(R.string.sleep_row_in), perRow = 3) {
            for (length in SleepTimerDefaults.IN_MINUTES) {
                SleepChip(
                    text = stringResource(R.string.sleep_minutes_short, length),
                    selected = minutes == length,
                    onClick = { onValueChange(length.toFloat()) },
                )
            }
        }

        // At: the next quarters of the hour, as the clock will read.
        ChipRow(stringResource(R.string.sleep_row_at)) {
            val times = listOf(15L, 30L, 45L, 60L).map { getNextInterval(it) }.sortedBy { it.second }
            for ((timeString, length) in times) {
                SleepChip(
                    text = timeString,
                    selected = minutes == length.roundToInt(),
                    onClick = { onValueChange(length) },
                )
            }
        }

        // End of: starts at once, as the end of the song always did.
        ChipRow(stringResource(R.string.sleep_row_end_of)) {
            SleepChip(stringResource(R.string.sleep_end_song)) { onEnd(SleepEnd.SONG) }
            SleepChip(stringResource(R.string.sleep_end_album)) { onEnd(SleepEnd.ALBUM) }
            SleepChip(stringResource(R.string.queue)) { onEnd(SleepEnd.QUEUE) }
        }
    }
}

/**
 * The running timer: the time left, large, and when it stops, then the chips that move it. A timer
 * that waits for an end has its end over the time, since the time is only an estimate of it.
 */
@Composable
private fun RunningTimer(status: SleepTimer.Status, timer: SleepTimer, onEndOfSong: () -> Unit) {
    val context = LocalContext.current
    val end = status.end
    val endName = when (end) {
        SleepEnd.SONG -> stringResource(R.string.end_of_song)
        SleepEnd.ALBUM -> stringResource(R.string.sleep_end_of_album)
        SleepEnd.QUEUE -> stringResource(R.string.sleep_end_of_queue)
        null -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
        if (endName != null) {
            Text(
                text = endName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            // Where the time to an end cannot be told, the end is the answer.
            text = status.leftMs?.let { makeTimeString(it) } ?: endName.orEmpty(),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        status.stopAtMs?.let { stopAt ->
            val clock = DateFormat.getTimeFormat(context).format(Date(stopAt))
            Text(
                text = stringResource(if (status.afterSong) R.string.sleep_stops_at_after_song else R.string.sleep_stops_at, clock),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        for (minutes in SleepTimerDefaults.EXTEND_MINUTES) {
            SleepChip(stringResource(R.string.sleep_plus_minutes, minutes)) { timer.extend(minutes) }
        }
        SleepChip(stringResource(R.string.end_of_song), selected = end == SleepEnd.SONG, onClick = onEndOfSong)
    }
}

/**
 * The three switches: Let the song finish, Fade out and Every night. Each is a setting that is
 * stored as it is flipped, so a running timer takes it at once and the settings pages show it.
 * Let the song finish is for a timer with a time, and is dimmed under one that waits for an end.
 */
@Composable
private fun SleepSwitches(letFinishApplies: Boolean) {
    val (letFinish, onLetFinish) = rememberPreference(SleepTimerSongFinishKey, SleepTimerDefaults.LET_SONG_FINISH)
    val (fade, onFade) = rememberPreference(SleepTimerFadeKey, SleepTimerDefaults.FADE_ENABLED)
    val (fadeSeconds, _) = rememberPreference(SleepTimerFadeDurationKey, SleepTimerDefaults.FADE_DURATION_SECONDS)
    val (night, onNight) = rememberPreference(SleepScheduleKey, false)
    val (storedStart, onStart) = rememberPreference(SleepScheduleStartKey, ScheduledSleep.DEFAULT_START)
    val start = ScheduledSleep.startOf(storedStart)
    var showStart by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        SwitchRow(checked = letFinish, enabled = letFinishApplies, onChange = onLetFinish) {
            Text(stringResource(R.string.sleep_let_finish), style = MaterialTheme.typography.bodyLarge)
        }
        SwitchRow(checked = fade, onChange = onFade) {
            Text(stringResource(R.string.sleep_fade_row, fadeSeconds), style = MaterialTheme.typography.bodyLarge)
        }
        SwitchRow(checked = night, onChange = onNight) {
            Text(stringResource(R.string.sleep_every_night), style = MaterialTheme.typography.bodyLarge)
            // The gaps either side of the dot are the width of a space, as in "Fade out · 30 s" above.
            Spacer(Modifier.width(SPACE_WIDTH))
            Text("·", style = MaterialTheme.typography.bodyLarge)
            // The time is its own button: the same picker as in Player and audio, and the same stored time.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = stringResource(R.string.sleep_schedule_start)) { showStart = true }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = SPACE_WIDTH),
            ) {
                Text(
                    text = formatTimeOfDay(LocalContext.current, start),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showStart) {
        TimeOfDayDialog(
            title = stringResource(R.string.sleep_schedule_start),
            minuteOfDay = start,
            onDismiss = { showStart = false },
            onConfirm = {
                showStart = false
                onStart(it)
            },
        )
    }
}

/** A line of the sheet that is a switch: the whole row flips it, as in the settings. */
@Composable
private fun SwitchRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    ) {
        // Only the words dim where the switch does not apply: the switch has its own look for that,
        // and dimmed twice it was a dark dot on the sheet.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else 0.5f),
            content = label,
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A labelled row of chips, centred, that wraps where it must, and at [perRow] at the most. */
@Composable
private fun ChipRow(label: String, perRow: Int = Int.MAX_VALUE, chips: @Composable FlowRowScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = perRow,
            content = chips,
        )
    }
}

/** One chip: outlined, and filled where it is what is chosen. */
@Composable
private fun SleepChip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 16.dp)
    if (selected) {
        FilledTonalButton(onClick = onClick, contentPadding = padding, modifier = Modifier.height(40.dp)) {
            Text(text, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = padding, modifier = Modifier.height(40.dp)) {
            Text(text, maxLines = 1)
        }
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
