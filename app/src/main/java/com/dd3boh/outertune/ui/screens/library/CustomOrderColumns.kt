/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.library

/**
 * How many rows abreast a library list stands while its order can be changed by hand ([canDrag]: Custom order,
 * unlocked): one, since rows are dragged one under another and two abreast would have no handles. Otherwise
 * [columns], what the window allows (two on its side). Locking the order brings the two back.
 */
fun columnsWhileOrdering(columns: Int, canDrag: Boolean): Int = if (canDrag) 1 else columns
