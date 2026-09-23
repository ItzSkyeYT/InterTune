/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.backButtonSurface
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.utils.rememberGlassSpec

/**
 * The top of a screen the way One UI 8 draws it: no bar at all, just a back circle and the title
 * in a pill beside it, floating over the content, which scrolls up underneath them. Measured from
 * Samsung's Settings and Weather on a Galaxy S25 Ultra: both are 48dp tall and centred on the same
 * line, the circle 12dp from the edge, the pill's text bold and about 18sp.
 *
 * It stays put rather than sliding away with the content, as Samsung's does. A bar that is only
 * two floating shapes has nothing to collapse, and letting them ride up under the status bar would
 * cut them in half. A fade behind the status bar keeps the clock readable over whatever passes
 * beneath it.
 *
 * [onBack] defaults to going up and [onLongBack], a long press on the circle, to the main screen.
 */
@Composable
fun FloatingTopBar(
    title: String?,
    navController: NavController,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { navController.navigateUp() },
    onLongBack: () -> Unit = { navController.backToMain() },
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopBarInsets,
) = FloatingTopBar(
    titleContent = { if (!title.isNullOrEmpty()) TopBarTitle(title) },
    navController = navController,
    modifier = modifier,
    onBack = onBack,
    onLongBack = onLongBack,
    actions = actions,
    windowInsets = windowInsets,
)

/** The same bar with anything in the title's place: a search field in a [TopBarPill], say. */
@Composable
fun FloatingTopBar(
    titleContent: @Composable () -> Unit,
    navController: NavController,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { navController.navigateUp() },
    onLongBack: () -> Unit = { navController.backToMain() },
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopBarInsets,
) {
    // Laid out by hand rather than with Material's TopAppBar, which puts a touch handler across
    // its whole width. On an opaque bar that did no harm; on this one the rows scrolled beneath it
    // could be seen between the shapes and not tapped or dragged. Here only the circle and the
    // pills take touches, and the slots keep TopAppBar's own 4dp paddings, which the 12dp edges
    // and the 8dp gaps are measured against.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .topBarFade()
            .semantics {
                isTraversalGroup = true
                // Composed after the content it floats over, so TalkBack would reach it last.
                traversalIndex = -1f
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .windowInsetsPadding(windowInsets)
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    modifier = Modifier.backButtonSurface(),
                    onClick = onBack,
                    onLongClick = onLongBack,
                ) {
                    Icon(BackChevron, contentDescription = stringResource(R.string.back))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { titleContent() }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** The title pill's text: bold, about 18sp, which is what Samsung's measured. */
val TopBarTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)

/** The title, bold, in a pill the height of the back circle. Nothing at all while it is empty. */
@Composable
fun TopBarTitle(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    TopBarPill(modifier) {
        Text(
            text = text,
            style = TopBarTitleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/**
 * A search box in the title's place: the same pill, full width, with the title's 18sp text.
 *
 * Not Material's TextField in a pill. That one is at least 56dp tall with 16dp above and below the
 * text, so inside the 48dp pill the placeholder came out cut in half along its bottom edge.
 */
@Composable
fun TopBarSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search),
) {
    val style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
    // 4dp short of the slot's end, so the pill stops 12dp from the edge like the circles do.
    TopBarPill(Modifier.fillMaxWidth().padding(end = 4.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                }
            },
        )
    }
}

/** The pill itself, for a title that is more than text. */
@Composable
fun TopBarPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            // At least the circle's 48dp, taller only when the content needs it: the folder
            // screen's two lines at a large font size.
            .heightIn(min = 48.dp)
            .background(topBarSurfaceColor(), CircleShape)
            // Opaque, so a tap on it must not reach a row hidden behind it.
            .pointerInput(Unit) {},
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/**
 * A screen's trailing buttons, grouped on one pill: a single button becomes a circle mirroring the
 * back button, 12dp from the other edge, and two share one longer pill, as One UI groups them.
 */
@Composable
fun TopBarActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .height(48.dp)
            .background(topBarSurfaceColor(), CircleShape)
            .pointerInput(Unit) {},
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * What the circle and the pills are filled with: a near-neutral grey a couple of tones off the
 * page, the way Samsung's #262626 sits on its black one.
 *
 * Not the glass tint any more. That is surfaceColorAtElevation, which carries the album colour at
 * 70% alpha, and on the bar it came out at 1.06 to 1.14 to 1 against its surroundings: there, but
 * only just. surfaceContainerHigh in the dark is tone 17 against Samsung's 15; in the light the
 * page is tone 98, so a white disc would vanish, and the highest container (tone 90) is the
 * lightest that still reads. On glass it is nearly opaque, never a backdrop read: see
 * BackButtonSurface.kt for why reading the backdrop in here kills the app.
 */
@Composable
fun topBarSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val base = if (scheme.surface.luminance() < 0.5f) scheme.surfaceContainerHigh else scheme.surfaceContainerHighest
    return if (rememberGlassSpec() != null) base.copy(alpha = 0.94f) else base
}

/** A fade from the page colour behind the status bar to nothing at the bar's bottom edge. */
@Composable
private fun Modifier.topBarFade(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    return background(Brush.verticalGradient(0f to surface.copy(alpha = 0.97f), 0.4f to surface.copy(alpha = 0.8f), 1f to Color.Transparent))
}

/**
 * Samsung's back chevron: 9.6 by 18dp, a 1.5dp stroke with round ends, where every Material chevron
 * is a 2.5dp stroke, a good half heavier. Drawn 1.3dp left of centre, as Samsung's is, since that
 * is where the eye puts a chevron's middle; mirroring right to left moves that the right way too.
 */
val BackChevron: ImageVector by lazy {
    ImageVector.Builder(
        name = "BackChevron",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(14.75f, 3.75f)
        lineTo(6.65f, 12f)
        lineTo(14.75f, 20.25f)
    }.build()
}
