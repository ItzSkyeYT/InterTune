/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.mix.edit.MixPreset

/** What the naming dialog is open for: a new preset, or one being renamed. */
private sealed interface Naming {
    data object New : Naming
    data class Rename(val preset: MixPreset) : Naming
}

/**
 * The listener's own presets in the row of presets, after the app's (his ask of 9 Nov 2026: "let me
 * create saved presets for the transitions"), as a DAW lists the user's presets beside its own: a
 * tap lays one on the change, heard at once; a long press offers Rename and Delete, as a right
 * click does in Ableton's browser; New preset keeps the change as it is under a name.
 * [onApplied] is told after a preset is laid on, [onLimit] where it cannot fit these two songs,
 * [onDeleted] after a delete, with the preset and where it was, for an Undo. Each chip reports its
 * place to [row], which keeps the one lit in view.
 *
 * For TalkBack a chip is one node, the chip's own: its name, whether it is lit, a double tap that
 * lays it on and its options as an action. The layer over it that takes a long press is out of the
 * tree: it was the node that answered a double tap, and it had no name (10 Nov 2026).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserPresetChips(
    vm: MixEditorViewModel,
    editor: MixEditor,
    presets: List<MixPreset>,
    lit: MixPreset?,
    canSave: Boolean,
    enabled: Boolean,
    onApplied: () -> Unit,
    onLimit: (Limit) -> Unit,
    onDeleted: (MixPreset, Int) -> Unit,
    row: PresetRow? = null,
) {
    var naming by remember { mutableStateOf<Naming?>(null) }
    for ((index, p) in presets.withIndex()) {
        var menu by remember(p.id) { mutableStateOf(false) }
        val source = remember(p.id) { MutableInteractionSource() }
        val selected = lit?.id == p.id
        val options = stringResource(R.string.mix_preset_options)
        val lay = {
            val limit = vm.edit { applyPreset(p) }
            if (limit != null) onLimit(limit) else onApplied()
        }
        Box(if (row != null) row.chip(p.id) else Modifier) {
            FilterChip(
                selected = selected,
                enabled = enabled,
                onClick = lay,
                interactionSource = source,
                label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                leadingIcon = if (selected) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null,
                // Its name and whether it is lit are the chip's own; a long press has no touch of its own for TalkBack, so an action.
                modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(options) { menu = true; true }) },
            )
            // Over the chip, so a long press can open its menu: the chip draws the press it is shown.
            // Out of the accessibility tree: the chip above is the node there.
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .combinedClickable(
                        interactionSource = source,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onLongClickLabel = options,
                        onLongClick = { menu = true },
                        onClick = lay,
                    ),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.mix_preset_rename)) }, onClick = { menu = false; naming = Naming.Rename(p) })
                DropdownMenuItem(text = { Text(stringResource(R.string.mix_preset_delete)) }, onClick = {
                    menu = false
                    vm.deletePreset(p)
                    onDeleted(p, index)
                })
            }
        }
    }
    AssistChip(
        onClick = { naming = Naming.New },
        enabled = enabled && canSave,
        label = { Text(stringResource(R.string.mix_preset_new), maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(AssistChipDefaults.IconSize)) },
    )
    naming?.let { n ->
        val first = when (n) {
            Naming.New -> stringResource(R.string.mix_preset_default_name, presets.size + 1)
            is Naming.Rename -> n.preset.name
        }
        var name by remember(n) { mutableStateOf(first) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(if (n is Naming.Rename) R.string.mix_preset_rename else R.string.mix_preset_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mix_preset_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    when (n) {
                        Naming.New -> vm.savePreset(name)
                        is Naming.Rename -> vm.renamePreset(n.preset, name)
                    }
                    naming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** A name's longest, as a chip shows it whole up to about this. */
private const val MAX_NAME = 40
