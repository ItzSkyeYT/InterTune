/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.testerlock

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.PureBlackKey
import com.dd3boh.outertune.ui.theme.OuterTuneTheme
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The #testers channel on Discord: the newest build is there, and the button that moves a code. */
private const val TESTERS_CHANNEL = "https://discord.com/channels/1544002995887411230/1559515871305666560"

/**
 * The gate a locked tester build opens on, in place of the whole app until a code passes: the
 * build's icon, what it is, a field for the code, Open, the door's answer, and where a code comes from.
 *
 * What it shows for each answer of the door is [TesterGateScreen]'s, and drawn here: a code the door
 * refused keeps the field, with Paste at its end while it is empty; a good code refused for its build
 * (out of date) or its device (in use) has no field, and a button to #testers instead.
 *
 * Drawn before the app's own theme is, so it puts on the same one itself, light or dark as the
 * app is set.
 */
@Composable
internal fun TesterGate(
    activity: ComponentActivity,
    state: GateState,
    onOpen: (String) -> Unit,
    onEdited: () -> Unit,
    onAnotherCode: () -> Unit,
) {
    val darkMode by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
    val systemDark = isSystemInDarkTheme()
    val dark = if (darkMode == DarkMode.AUTO) systemDark else darkMode == DarkMode.ON
    LaunchedEffect(dark) {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    OuterTuneTheme(context = activity, darkTheme = dark, pureBlack = pureBlack, highContrastCompat = false) {
        GatePage(activity, state, onOpen, onEdited, onAnotherCode)
    }
}

@Composable
private fun GatePage(
    activity: ComponentActivity,
    state: GateState,
    onOpen: (String) -> Unit,
    onEdited: () -> Unit,
    onAnotherCode: () -> Unit,
) {
    val field = rememberTextFieldState(state.prefill)
    // The answer under the field is about the code it was given: it goes as soon as that code is
    // changed, not at the next Open.
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.drop(1).collect { onEdited() }
    }
    val screen = TesterGateScreen.of(state.message)
    // A phone on its side: what the build is goes beside the field, so the field is not under the
    // fold. Told from the window, which the keyboard does not make shorter.
    val window = LocalWindowInfo.current.containerSize
    val beside = window.width > window.height && with(LocalDensity.current) { window.height.toDp() } < 480.dp
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // The bars, the camera's cutout, and the keyboard.
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val height = maxHeight
            if (beside) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                    Pane(height, Modifier.weight(1f)) { Heading(iconSize = 64.dp, screen) }
                    Pane(height, Modifier.weight(1f)) { Form(activity, state, screen, field, onOpen, onAnotherCode) }
                }
            } else {
                Pane(height, Modifier.fillMaxSize()) {
                    Heading(iconSize = 96.dp, screen)
                    Spacer(Modifier.height(28.dp))
                    Form(activity, state, screen, field, onOpen, onAnotherCode)
                }
            }
        }
    }
}

/** A column that sits in the middle of [height] while it fits, and scrolls when it does not. */
@Composable
private fun Pane(height: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = height)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            content = content,
        )
    }
}

/** What the door's answer says, in words. */
private fun GateMessage.words(): Int = when (this) {
    GateMessage.UNKNOWN -> R.string.tester_lock_unknown
    GateMessage.REVOKED -> R.string.tester_lock_revoked
    GateMessage.IN_USE -> R.string.tester_lock_in_use
    GateMessage.OUTDATED -> R.string.tester_lock_outdated
    GateMessage.GRACE_OVER -> R.string.tester_lock_not_checked
}

@Composable
private fun Heading(iconSize: Dp, screen: GateScreen) {
    AppIcon(iconSize)
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.tester_lock_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
    )
    Spacer(Modifier.height(8.dp))
    // With no field to enter a code in, the answer is what is said here, in place of asking for a code.
    val lead = if (screen.field) R.string.tester_lock_text else screen.message?.words() ?: R.string.tester_lock_text
    Text(
        text = stringResource(lead),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** The build's own launcher icon, site tape and all, in the shape the phone gives icons. */
@Composable
private fun AppIcon(size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(px) {
        context.applicationInfo.loadIcon(context.packageManager).toBitmap(px, px).asImageBitmap()
    }
    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
}

@Composable
private fun Form(
    activity: ComponentActivity,
    state: GateState,
    screen: GateScreen,
    field: TextFieldState,
    onOpen: (String) -> Unit,
    onAnotherCode: () -> Unit,
) {
    if (screen.field) CodeForm(state, screen, field, onOpen) else TestersForm(activity, screen, field, onAnotherCode)
}

@Composable
private fun CodeForm(state: GateState, screen: GateScreen, field: TextFieldState, onOpen: (String) -> Unit) {
    val open = { onOpen(field.text.toString()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        state = field,
        enabled = !state.busy,
        label = { Text(stringResource(R.string.tester_lock_field)) },
        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = screen.message?.let { message -> { Text(stringResource(message.words())) } },
        isError = state.message == GateMessage.UNKNOWN || state.message == GateMessage.REVOKED,
        // While the field is empty, a tap on Paste is the one time the clipboard is read.
        trailingIcon = if (TesterGateScreen.pasteShown(screen, field.text.isEmpty(), state.busy)) {
            { TextButton(onClick = { scope.launch { pasteInto(field, clipboard) } }) { Text(stringResource(R.string.tester_lock_paste)) } }
        } else null,
        outputTransformation = CodeAsSent,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            // A password field to the keyboard, so it offers no words of its own for a code. The code
            // stays in sight: only a transformation hides text in Compose, and CodeAsSent shows it.
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        onKeyboardAction = { open() },
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = open,
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.tester_lock_open))
    }
    if (screen.help) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.tester_lock_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A good code the door refused the build with: no field, since the code is not the trouble. A button to
 * #testers, where the newest build is and where /code moves a code, and what is left to say under it.
 */
@Composable
private fun TestersForm(activity: ComponentActivity, screen: GateScreen, field: TextFieldState, onAnotherCode: () -> Unit) {
    Button(
        onClick = { openTesters(activity) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.tester_lock_open_testers))
    }
    if (screen.codeIsFine) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.tester_lock_code_fine),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (screen.anotherCode) {
        Spacer(Modifier.height(8.dp))
        // The field comes back empty: the code that was in it is the one that is in use.
        TextButton(onClick = { field.clearText(); onAnotherCode() }) {
            Text(stringResource(R.string.tester_lock_other_code))
        }
    }
}

/**
 * The #testers channel, in the Discord app when it is installed and in the browser when it is not: the
 * phone picks, as it does for any link. Nothing is sent from here, and a phone with neither opens nothing.
 */
private fun openTesters(activity: ComponentActivity) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, TESTERS_CHANNEL.toUri()))
    } catch (e: ActivityNotFoundException) {
        // No app and no browser to show a link: nothing to open.
    }
}

/**
 * What the clipboard holds, put in the field, which draws it as it draws typing. The one place the
 * clipboard is read, and only from a tap on Paste: never on its own, when the gate opens or at any other time.
 */
private suspend fun pasteInto(field: TextFieldState, clipboard: Clipboard) {
    val clip = clipboard.getClipEntry()?.clipData ?: return
    val pasted = if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
    val code = TesterCode.fromPasted(pasted.orEmpty())
    if (code.isNotEmpty()) field.setTextAndPlaceCursorAtEnd(code)
}

/**
 * The code drawn as it will be sent, XXXX-XXXX-XXXX as it is typed: in capitals, O, I and L as 0, 1
 * and 1, the typed spaces and dashes left out and its own dashes drawn in. Only drawn: what is typed
 * stays as it was typed, and Open reads it the same way (TesterCode.normalise). Changing the typed
 * text itself, as the field first did, put the keyboard out of step with it, and Compose's text field
 * then crashed on the keyboard's next word (emulator, 29 Oct).
 */
private val CodeAsSent = OutputTransformation {
    // From the end, so a change does not move what is still to be looked at.
    for (i in length - 1 downTo 0) {
        val typed = charAt(i)
        val read = TesterCode.read(typed)
        if (read != typed.toString()) replace(i, i + 1, read)
    }
    for (at in TesterCode.dashesBefore(length).asReversed()) insert(at, "-")
}
