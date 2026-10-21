/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Intent
import android.os.Build
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.utils.UpdateInstaller
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.utils.InstallSource
import com.dd3boh.outertune.utils.installSource
import com.dd3boh.outertune.utils.fdroidPageUrl
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.UpdateChecker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button

/**
 * Asks whether to install an update, and shows what is in it.
 *
 * Shown whether or not automatic downloading is on, because Android will not install without a
 * confirmation from the user anyway. The only difference the setting makes is whether the apk is
 * already on the device when this appears, which is the difference between install being instant
 * and install having to fetch ten megabytes first.
 *
 * Three ways out on purpose. Cancel is "not this version, stop asking for now", later is "ask me
 * again in an hour", and install is the one that acts. Dismissing by tapping outside is the same as
 * later rather than the same as cancel, because a mis-tap should not silently bury an update.
 *
 * From the tap on Install until Android has installed it, [busy] turns the same dialog into one
 * that says what is happening, with no Install button to tap again. Android's own window says
 * nothing once its button is tapped, and the app used to show nothing either, so people tapped
 * Install again and again: in the prompt the taps were lost, and in Settings each one started the
 * install over.
 */
@Composable
fun UpdatePrompt(
    update: UpdateChecker.Update,
    /** True when the apk is already downloaded, so install will not have to wait. */
    ready: Boolean,
    /** True when Android has not yet been told this app may install apps. */
    needsPermission: Boolean,
    /** Why the last try did not install, or what will stop the next one; null when nothing is known. */
    trouble: UpdateTrouble? = null,
    failedReason: String? = null,
    /** While the apk downloads after a tap on Install: 0 to 100, or -1 when its size is unknown. */
    downloadPercent: Int? = null,
    /** While Android has the install: what to say in place of the offer. Null for the offer. */
    busy: UpdateWaitPhase? = null,
    onShowWindowAgain: () -> Unit = {},
    onStop: () -> Unit = {},
    /** Back or a tap outside while Android's window waits. The install is left as it is. */
    onPutAway: () -> Unit = {},
    onOpenAutoBlocker: () -> Unit = {},
    onTryAgain: () -> Unit = {},
    onInstall: () -> Unit,
    onRemindLater: () -> Unit,
    onCancel: () -> Unit,
) {
    // Where this copy came from decides what the primary button should do. An F-Droid install is
    // updated by F-Droid, so it is not offered an apk from GitHub. Android would accept one, since
    // F-Droid's build is reproducible and ships the same signature, but whoever chose F-Droid
    // chose its checks and its timing along with it.
    val context = LocalContext.current
    val fromFdroid = remember { context.installSource() == InstallSource.F_DROID }

    // The offer goes on Back or a tap outside, as later. The busy form stays, but for the wait,
    // which steps aside without touching the install: Stop is the way out of that one.
    val dismissable = busy == null || updateWaitDismissable(busy)

    // One dialog for both, so the prompt turns into the busy form in place instead of closing and
    // another one opening.
    AlertDialog(
        onDismissRequest = when {
            busy == null -> onRemindLater
            dismissable -> onPutAway
            else -> ({})
        },
        properties = DialogProperties(dismissOnBackPress = dismissable, dismissOnClickOutside = dismissable),
        title = {
            Text(
                stringResource(
                    if (busy != null) R.string.update_wait_title else R.string.update_prompt_title,
                    update.versionName,
                )
            )
        },
        text = {
            if (busy != null) {
                UpdateWaitText(busy)
                return@AlertDialog
            }
            // Scrolls on a short screen, where the trouble line and four buttons did not fit and
            // the words were cut off.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Download state decides the line, and the permission wording is only used when
                // the apk really is downloaded, because that string says so. Checking permission
                // first would have claimed "downloaded and ready" with nothing downloaded, which
                // is the usual case: automatic downloading is off by default. On F-Droid there is
                // no line here at all: the button below goes to F-Droid, not to an install, so
                // none of these would be true, and the F-Droid note underneath says where the
                // update comes from.
                // A known trouble replaces the line: it is the one thing worth reading now.
                if (trouble != null) {
                    Text(
                        text = if (trouble == UpdateTrouble.FAILED) {
                            stringResource(trouble.stringRes, failedReason ?: "")
                        } else {
                            stringResource(trouble.stringRes)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (downloadPercent != null) {
                    // Fifty megabytes on a phone's data can take a minute: without this the prompt
                    // sat unchanged after the tap, as if the tap had done nothing.
                    if (downloadPercent >= 0) {
                        val shown by animateFloatAsState(downloadPercent / 100f, label = "updateDownload")
                        LinearProgressIndicator(
                            progress = { shown },
                            strokeCap = StrokeCap.Round,
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp),
                        )
                    }
                } else updatePromptLine(fromFdroid, ready, needsPermission)?.let { line ->
                    Text(
                        text = stringResource(line.stringRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (fromFdroid) {
                    Text(
                        // Deliberately does not promise the version is there yet. F-Droid builds
                        // each release itself and usually lands a while after the tag, so telling
                        // someone it is waiting for them would often be untrue.
                        text = stringResource(R.string.update_fdroid_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (update.changelog.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    // Capped and scrollable: release notes run long, and a dialog that grows past
                    // the screen loses its buttons.
                    Text(
                        text = remember(update.changelog) { tidyChangelog(update.changelog) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        // Three actions do not fit the two slots a dialog gives you, and squeezing the third in
        // beside the others produced a lopsided L. Material's answer for three is to stack them
        // full width in priority order, which also gives each one a proper touch target.
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Before anything else, so no busy form can ever get the Install button below.
                if (busy != null) {
                    UpdateWaitButtons(busy, onShowWindowAgain = onShowWindowAgain, onStop = onStop)
                    return@Column
                }
                if (trouble != null) {
                    TroubleButtons(
                        trouble = trouble,
                        releaseUrl = update.releaseUrl,
                        onOpenAutoBlocker = onOpenAutoBlocker,
                        onTryAgain = onTryAgain,
                        onRemindLater = onRemindLater,
                        onSkip = onCancel,
                    )
                    return@Column
                }
                Button(
                    onClick = {
                        if (fromFdroid) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, fdroidPageUrl(context.packageName).toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            // Later rather than cancel. They have been sent to F-Droid, not told
                            // this version is unwanted, and if F-Droid has not built it yet they
                            // should be asked again.
                            onRemindLater()
                        } else {
                            onInstall()
                        }
                    },
                    enabled = downloadPercent == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            when {
                                fromFdroid -> R.string.update_prompt_fdroid
                                needsPermission -> R.string.update_prompt_allow
                                else -> R.string.update_prompt_install
                            }
                        )
                    )
                }
                TextButton(
                    onClick = onRemindLater,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_prompt_later))
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.update_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = null,
    )
}

/**
 * The busy form's words: the download's bar, kept so the prompt seems to turn into this rather than
 * give way to it, and a line for the phase. The bar never shows a share. Android reports a single
 * step between the yes and the end, and a bar parked at ninety percent would look stuck.
 */
@Composable
private fun UpdateWaitText(phase: UpdateWaitPhase) {
    Column {
        LinearProgressIndicator(
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp),
        )
        updateWaitLine(phase)?.let { line ->
            Text(
                text = if (phase == UpdateWaitPhase.WAITING) {
                    stringResource(line, stringResource(androidUpdateButton(Build.VERSION.SDK_INT)))
                } else {
                    stringResource(line)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Full width and stacked, as the prompt's own buttons are. */
@Composable
private fun UpdateWaitButtons(phase: UpdateWaitPhase, onShowWindowAgain: () -> Unit, onStop: () -> Unit) {
    for (button in updateWaitButtons(phase)) {
        when (button) {
            UpdateWaitButton.SHOW_WINDOW -> Button(onClick = onShowWindowAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_wait_show_window))
            }

            UpdateWaitButton.STOP -> TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.update_wait_stop),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What to offer when an install did not go through. With Auto Blocker, the release page would not
 * help (a browser's install is refused the same way), so the way out is Auto Blocker's own switch.
 */
@Composable
private fun TroubleButtons(
    trouble: UpdateTrouble,
    releaseUrl: String,
    onOpenAutoBlocker: () -> Unit,
    onTryAgain: () -> Unit,
    onRemindLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val autoBlocker = trouble == UpdateTrouble.BLOCKED_SAMSUNG
    Button(
        onClick = if (autoBlocker) onOpenAutoBlocker else onTryAgain,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (autoBlocker) R.string.update_open_auto_blocker else R.string.update_try_again))
    }
    // With Auto Blocker turned off some other way, Try again is the short way back to installing.
    if (autoBlocker) {
        TextButton(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_try_again))
        }
    } else {
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, releaseUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.update_open_page))
        }
    }
    TextButton(onClick = onRemindLater, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.update_prompt_later))
    }
    // Someone who keeps Auto Blocker on would otherwise be asked again every time.
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.update_skip),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Why an install did not or will not go through, as far as the app can tell. */
enum class UpdateTrouble(val stringRes: Int) {
    /** Refused or never answered, on a phone that has Auto Blocker. */
    BLOCKED_SAMSUNG(R.string.update_blocked_samsung),
    BLOCKED(R.string.update_blocked),
    FAILED(R.string.update_install_failed),
}

fun updateTrouble(
    fromFdroid: Boolean,
    blocked: Boolean,
    failed: Boolean,
    samsung: Boolean,
): UpdateTrouble? = when {
    // F-Droid installs are F-Droid's to update: the button goes there, whatever this phone blocks.
    fromFdroid -> null
    blocked && samsung -> UpdateTrouble.BLOCKED_SAMSUNG
    blocked -> UpdateTrouble.BLOCKED
    failed -> UpdateTrouble.FAILED
    else -> null
}

/**
 * The prompt's first line: which of the three things it can say, decided from download state, or
 * null for no line at all. fromFdroid outranks the rest and reads as null, since the button
 * underneath sends the user to F-Droid rather than downloading or installing anything here, so no
 * line about either of those would be true, and the dialog already names the version in its title
 * and, just below, that F-Droid is where it comes from.
 */
internal enum class UpdatePromptLine(val stringRes: Int) {
    NEEDS_PERMISSION(R.string.update_prompt_needs_permission),
    READY(R.string.update_prompt_ready),
    WILL_DOWNLOAD(R.string.update_prompt_will_download),
}

internal fun updatePromptLine(fromFdroid: Boolean, ready: Boolean, needsPermission: Boolean): UpdatePromptLine? = when {
    fromFdroid -> null
    ready && needsPermission -> UpdatePromptLine.NEEDS_PERMISSION
    ready -> UpdatePromptLine.READY
    else -> UpdatePromptLine.WILL_DOWNLOAD
}

/**
 * What the dialog says from the tap on Install until Android has installed the update, or the try
 * ended another way. Android's own window says nothing after its button is tapped, and an update
 * of this app ends with Android closing it, so this is all the person gets to see of it.
 */
enum class UpdateWaitPhase {
    /** The apk is copied into Android's installer, or Android's window is about to open. */
    PREPARING,

    /**
     * Just after Android's window was opened, or just back from it, with no answer heard yet. The
     * answer lands within a second as a rule, and "tap Update" said in the meantime would be wrong
     * for everyone who just did.
     */
    SETTLING,

    /** Android's window was shown and is not answered: left with Home, or open beside the app. */
    WAITING,

    INSTALLING,
}

/** How long the wait says nothing after Android's window was opened, or the app came back from it. */
internal const val UPDATE_SETTLE_MS = 3_000L

/**
 * The phase for an installer state, or null when the prompt itself shows: the offer, a download,
 * and every way an install can end. [sinceWindowMs] is the time since Android's window was last
 * opened or the app last came back to the front, whichever was later.
 */
internal fun updateWaitPhase(state: UpdateInstaller.State, sinceWindowMs: Long): UpdateWaitPhase? = when (state) {
    UpdateInstaller.State.Preparing -> UpdateWaitPhase.PREPARING
    is UpdateInstaller.State.AwaitingConfirmation -> when {
        !state.shown -> UpdateWaitPhase.PREPARING
        sinceWindowMs < UPDATE_SETTLE_MS -> UpdateWaitPhase.SETTLING
        else -> UpdateWaitPhase.WAITING
    }

    UpdateInstaller.State.Installing -> UpdateWaitPhase.INSTALLING
    else -> null
}

internal fun updateWaitLine(phase: UpdateWaitPhase): Int? = when (phase) {
    UpdateWaitPhase.PREPARING -> R.string.update_wait_preparing
    UpdateWaitPhase.SETTLING -> null
    UpdateWaitPhase.WAITING -> R.string.update_wait_waiting
    UpdateWaitPhase.INSTALLING -> R.string.update_wait_installing
}

/** None of them installs: a second tap on Install is what the busy form is there to prevent. */
internal enum class UpdateWaitButton { SHOW_WINDOW, STOP }

internal fun updateWaitButtons(phase: UpdateWaitPhase): List<UpdateWaitButton> = when (phase) {
    // Only for a window that waits. Started again once Android is installing, it would ask about
    // an install already under way.
    UpdateWaitPhase.WAITING -> listOf(UpdateWaitButton.SHOW_WINDOW, UpdateWaitButton.STOP)
    else -> emptyList()
}

/** Only the wait steps aside on Back or a tap outside, and it leaves the install as it is. */
internal fun updateWaitDismissable(phase: UpdateWaitPhase): Boolean = phase == UpdateWaitPhase.WAITING

/**
 * The word on Android's button for an update, as PackageInstallerActivity has it: "Install" up to
 * Android 11, "Update" from 12.
 */
internal fun androidUpdateButton(sdk: Int): Int =
    if (sdk >= Build.VERSION_CODES.S) R.string.update_wait_android_update else R.string.update_wait_android_install

/**
 * Makes GitHub release notes readable in a dialog.
 *
 * The notes are markdown written for a web page, so they arrive full of ## and ** which look like
 * debris on a phone. Rendering markdown properly would mean pulling in a renderer for one dialog,
 * so this just strips the syntax that actually shows up in these notes and leaves the words.
 */
private fun tidyChangelog(raw: String): String = raw
    .lineSequence()
    .filterNot { it.trim().matches(Regex("^[-*_]{1,}$")) }
    .map { line ->
        line.trim()
            .removePrefix("###").removePrefix("##").removePrefix("#")
            .replace("**", "")
            .replace(Regex("^[-*]\\s+"), "\u2022 ")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .trim()
    }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
