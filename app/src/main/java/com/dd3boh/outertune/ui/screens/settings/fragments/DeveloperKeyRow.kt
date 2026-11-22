/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.ui.Modifier
import com.dd3boh.outertune.ui.screens.settings.settingRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.dd3boh.outertune.R
import com.dd3boh.outertune.together.DevBadge
import com.dd3boh.outertune.together.DevKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import kotlinx.coroutines.launch

/**
 * "Developer key", in the developer block: where InterTune's developer pastes the developer key on a phone of his, so
 * that rooms show his badge (together/DevBadge, DevKey). It says only whether the key is set, never the key: the field
 * starts empty and hides what is pasted in, and only the developer key itself is kept. Remove takes it off the phone.
 */
@Composable
internal fun DeveloperKeyRow() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val set by remember { DevKey.isSet(context) }.collectAsState(initial = false)
    var asking by remember { mutableStateOf(false) }

    PreferenceEntry(
        modifier = Modifier.settingRow(R.string.dev_key),
        title = { Text(stringResource(R.string.dev_key)) },
        description = stringResource(if (set) R.string.dev_key_set else R.string.dev_key_not_set),
        icon = { Icon(Icons.Filled.Build, null) },
        onClick = { asking = true },
    )

    if (asking) {
        var typed by remember { mutableStateOf("") }
        val valid = remember(typed) { DevBadge.keyOf(typed) != null }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.dev_key)) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.dev_key_paste)) },
                    singleLine = true,
                    // A password field: hidden, and no keyboard learns or suggests it.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = typed.isNotBlank() && !valid,
                    supportingText = if (typed.isNotBlank() && !valid) {
                        { Text(stringResource(R.string.dev_key_wrong)) }
                    } else null,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val text = typed
                        typed = ""
                        asking = false
                        coroutineScope.launch { DevKey.put(context, text) }
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                if (set) {
                    TextButton(
                        onClick = {
                            asking = false
                            coroutineScope.launch { DevKey.clear(context) }
                        },
                    ) { Text(stringResource(R.string.dev_key_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { asking = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
