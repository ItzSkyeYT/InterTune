/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.recap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.recap.RecapDoor

/**
 * "Your InterTune Recap" at the top of Settings, in the Recap's own colours, from its reveal day
 * until the next year's: the way back to it after its pop-up, whenever wanted. [year] is the one
 * on show (rememberRecapOnShow). A tap opens it full screen, as it opened the first time.
 */
@Composable
fun RecapBanner(year: Int, modifier: Modifier = Modifier) {
    val shape = CardDefaults.elevatedShape
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button) { RecapDoor.open(year) }
    ) {
        BrandBackdrop(Modifier.matchParentSize())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(start = 18.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        ) {
            RecapMark(40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.recap_banner_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    stringResource(R.string.recap_banner_line),
                    fontSize = 14.sp, lineHeight = 1.3.em, color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                year.toString(),
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(Color(0xFFFFB3B5), Color.White, RecapRed)),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.04).em,
                ),
            )
        }
    }
}
