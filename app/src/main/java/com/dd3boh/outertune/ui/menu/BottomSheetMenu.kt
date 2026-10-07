/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.menu

import kotlinx.coroutines.launch
import androidx.compose.material3.SheetValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import com.dd3boh.outertune.ui.utils.LocalLandscape
import com.dd3boh.outertune.ui.utils.top


@Stable
class MenuState @OptIn(ExperimentalMaterial3Api::class) constructor(
    val sheetState: SheetState,
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(content: @Composable ColumnScope.() -> Unit) {
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        isVisible = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = Color.Transparent,
) {
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val coroutineScope = rememberCoroutineScope()

    if (state.isVisible) {
        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            sheetState = state.sheetState,
            // On a phone on its side a menu is the width it is upright and no wider (Landscape.kt).
            sheetMaxWidth = LocalLandscape.current.panelWidth(BottomSheetDefaults.SheetMaxWidth),
            dragHandle = null,
            contentWindowInsets = { WindowInsets.safeDrawing },
            modifier = modifier
                .fillMaxHeight()

        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeDefaults.Large.top())
                    .background(background)
                    // Opened from the keys, the sheet goes all the way up. Half open, the arrow
                    // keys walked on into entries below the bottom of the screen, which nothing
                    // scrolled into view, since the menu's own list had them in view already.
                    .onFocusChanged {
                        if (it.hasFocus && inputModeManager.inputMode == InputMode.Keyboard &&
                            state.sheetState.currentValue == SheetValue.PartiallyExpanded
                        ) {
                            coroutineScope.launch { state.sheetState.expand() }
                        }
                    }
            ) {
                state.content(this)
            }
        }
    }
}
