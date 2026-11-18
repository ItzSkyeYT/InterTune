/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.downloadManager.DownloadsSimpleLook
import com.dd3boh.outertune.constants.MenuHiddenEntriesKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.utils.editOrSay
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The least an entry of a menu's grid stands: its icon, its name on one line, and room to press.
 * An entry was 96dp whatever was in it, so the rows of a menu stood further apart than the icons
 * are tall three times over. A name that takes two lines makes its own entry taller.
 */
val GridMenuItemHeight = 72.dp

/** Between an entry's icon and its name. */
private val GridMenuItemGap = 6.dp

/**
 * A three-dot menu's grid. With a [kind] (and Unreleased.MENU_CHOICES) the menu can be trimmed: holding
 * any entry starts editing it, the way Android's Quick Settings are edited. Each entry then wears a
 * minus, a tap moves it under Hidden with a plus, a tap there brings it back, and Done ends it. Each
 * change is kept at once (MenuHiddenEntriesKey), for every menu of the same kind. Under a trimmed menu a
 * line says how many entries it hides, with Edit, so nothing ever just goes missing.
 */
@Composable
fun GridMenu(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    kind: MenuKind? = null,
    content: LazyGridScope.() -> Unit,
) {
    if (kind == null || !Unreleased.MENU_CHOICES) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = modifier,
            contentPadding = contentPadding,
            content = content
        )
        return
    }
    val context = LocalContext.current
    val resources = context.resources
    val scope = rememberCoroutineScope()
    val stored by rememberPreference(MenuHiddenEntriesKey, defaultValue = "")
    val hidden = remember(stored, kind) { MenuChoices.hiddenIn(MenuChoices.parse(stored), kind) }
    var editing by rememberSaveable(kind) { mutableStateOf(false) }
    val names = remember(resources) { HashMap<Int, String>() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        val build = MenuBuild(
            nameOf = { id ->
                names.getOrPut(id) { MenuChoices.entryName(runCatching { resources.getResourceEntryName(id) }.getOrDefault(id.toString())) }
            },
            hidden = hidden,
            editing = editing,
            startEditing = { editing = true },
            toggle = { name ->
                // Read and written in one edit: two quick taps both count.
                scope.launch(NonCancellable) {
                    context.editOrSay {
                        it[MenuHiddenEntriesKey] = MenuChoices.write(MenuChoices.toggle(MenuChoices.parse(it[MenuHiddenEntriesKey]), kind, name))
                    }
                }
            },
        )
        MenuBuild.current = build
        try {
            content()
        } finally {
            MenuBuild.current = null
        }
        if (editing) {
            if (build.held.isNotEmpty()) {
                item(key = "menu:hidden", span = { GridItemSpan(maxLineSpan) }, contentType = "menu:hidden") { HiddenHeading() }
                build.held.forEach { it(this) }
            }
            item(key = "menu:done", span = { GridItemSpan(maxLineSpan) }, contentType = "menu:done") {
                DoneRow { editing = false }
            }
        } else if (build.hiddenHere > 0) {
            val count = build.hiddenHere
            item(key = "menu:count", span = { GridItemSpan(maxLineSpan) }, contentType = "menu:count") {
                HiddenCountRow(count) { editing = true }
            }
        }
    }
}

/** What an entry wears while its menu is edited: a minus to hide it, or, under Hidden, a plus to bring it back. */
private enum class MenuMark(@StringRes val action: Int) {
    HIDE(R.string.menu_edit_hide),
    SHOW(R.string.menu_edit_show),
}

/**
 * One entry of a menu's grid, laid out as its menu's hiding and editing say (MenuBuild): left out when
 * hidden (kept for Hidden while editing), with a minus while editing, else as always, a hold starting
 * the editing. Outside a menu that can be edited, as always.
 */
private fun LazyGridScope.menuEntry(
    @StringRes title: Int,
    onClick: () -> Unit,
    entry: @Composable (mark: MenuMark?, onClick: () -> Unit, onLongClick: (() -> Unit)?) -> Unit,
) {
    val menu = MenuBuild.current
    if (menu == null) {
        item { entry(null, onClick, null) }
        return
    }
    val name = menu.nameOf(title)
    when {
        name in menu.hidden -> {
            menu.hiddenHere++
            if (menu.editing) menu.held.add { item { entry(MenuMark.SHOW, { menu.toggle(name) }, null) } }
        }
        menu.editing -> item { entry(MenuMark.HIDE, { menu.toggle(name) }, null) }
        else -> item { entry(null, onClick, menu.startEditing) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridMenuEntry(
    modifier: Modifier,
    icon: @Composable BoxScope.() -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    mark: MenuMark?,
    label: @Composable () -> Unit,
) {
    // While editing every entry can be moved, one greyed for now too.
    val active = enabled || mark != null
    Box {
        Column(
            verticalArrangement = Arrangement.spacedBy(GridMenuItemGap),
            modifier = modifier
                .clip(ShapeDefaults.Large)
                .heightIn(min = GridMenuItemHeight)
                .combinedClickable(
                    enabled = active,
                    onClickLabel = mark?.let { stringResource(it.action) },
                    // TalkBack says what a double tap and hold does here.
                    onLongClickLabel = if (onLongClick != null) stringResource(R.string.menu_edit_start) else null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .alpha(if (enabled) 1f else 0.5f)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
                content = icon
            )
            label()
        }
        if (mark != null) {
            val (back, front) = when (mark) {
                MenuMark.HIDE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                MenuMark.SHOW -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
            }
            Icon(
                imageVector = if (mark == MenuMark.HIDE) Icons.Rounded.Remove else Icons.Rounded.Add,
                contentDescription = null,
                tint = front,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 10.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(back)
                    .padding(3.dp)
            )
        }
    }
}

@Composable
private fun HiddenHeading() {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 12.dp))
        Text(stringResource(R.string.menu_hidden), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.menu_hidden_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneRow(onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(onClick = onDone) { Text(stringResource(R.string.menu_edit_done)) }
    }
}

@Composable
private fun HiddenCountRow(count: Int, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.menu_hidden_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
    }
}

fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    @StringRes title: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = GridMenuItem(
    modifier = modifier,
    icon = {
        Icon(
            painter = painterResource(icon),
            contentDescription = null
        )
    },
    title = title,
    enabled = enabled,
    onClick = onClick
)

fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    icon: @Composable BoxScope.() -> Unit,
    @StringRes title: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = menuEntry(title, onClick) { mark, click, longClick ->
    GridMenuEntry(modifier, icon, enabled, click, longClick, mark) {
        Text(
            text = stringResource(title),
            // The height of a line in step with the size, for the name made smaller below: at
            // the style's own size it is the style's 20sp.
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = (20f / 14f).em),
            textAlign = TextAlign.Center,
            maxLines = 2,
            // A word wider than the entry, "(recommendations)" three abreast on a phone, makes
            // the name a little smaller instead of cut off. A name that fits is as it was.
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                stepSize = 0.5.sp,
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tint: @Composable () -> Color = { LocalContentColor.current },
    @StringRes title: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = GridMenuItem(
    modifier = modifier,
    icon = {
        Icon(
            imageVector = icon,
            tint = tint(),
            contentDescription = null
        )
    },
    title = title,
    enabled = enabled,
    onClick = onClick
)



fun LazyGridScope.DownloadGridMenu(
    @Download.State state: Int?,
    onRemoveDownload: () -> Unit,
    onDownload: () -> Unit,
) {
    when (state) {
        Download.STATE_COMPLETED -> {
            GridMenuItem(
                icon = Icons.Rounded.OfflinePin,
                title = R.string.remove_download,
                onClick = onRemoveDownload
            )
        }

        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
            GridMenuItem(
                icon = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                },
                // What a tap does, in the simpler ways compared in the debug app (DownloadsSimpleLook): the wheel says it downloads.
                title = if (DownloadsSimpleLook.last.plainWords) R.string.cancel_download else R.string.downloading,
                onClick = onRemoveDownload
            )
        }

        else -> {
            GridMenuItem(
                icon = Icons.Rounded.Download,
                title = R.string.action_download,
                onClick = onDownload
            )
        }
    }
}

fun LazyGridScope.DownloadGridMenu(
    localDateTime: LocalDateTime?,
    onRemoveDownload: () -> Unit,
    onDownload: () -> Unit,
) {
    val state = getDownloadState(localDateTime)
    when (state) {
        Download.STATE_COMPLETED -> {
            GridMenuItem(
                icon = Icons.Rounded.OfflinePin,
                title = R.string.remove_download,
                onClick = onRemoveDownload
            )
        }

        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
            GridMenuItem(
                icon = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                },
                // What a tap does, in the simpler ways compared in the debug app (DownloadsSimpleLook): the wheel says it downloads.
                title = if (DownloadsSimpleLook.last.plainWords) R.string.cancel_download else R.string.downloading,
                onClick = onRemoveDownload
            )
        }

        else -> {
            GridMenuItem(
                icon = Icons.Rounded.Download,
                title = R.string.action_download,
                onClick = onDownload
            )
        }
    }
}

fun LazyGridScope.SleepTimerGridMenu(
    modifier: Modifier = Modifier,
    sleepTimerTimeLeft: Long,
    enabled: Boolean = true,
    onClick: () -> Unit
) = menuEntry(R.string.sleep_timer, onClick) { mark, click, longClick ->
    // Always pressable: [enabled] says whether a timer runs, which only dims the moon.
    GridMenuEntry(
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Bedtime,
                contentDescription = null,
                modifier = Modifier.alpha(if (enabled) 1f else 0.5f)
            )
        },
        enabled = true,
        onClick = click,
        onLongClick = longClick,
        mark = mark,
    ) {
        Text(
            // The time left where it can be told: the end of a song whose length is not known has none.
            text = if (enabled && sleepTimerTimeLeft > 0) makeTimeString(sleepTimerTimeLeft) else stringResource(
                id = R.string.sleep_timer
            ),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
