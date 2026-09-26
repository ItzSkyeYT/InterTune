/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.Announcement
import com.dd3boh.outertune.utils.AnnouncementAction
import com.dd3boh.outertune.utils.AnnouncementText
import java.util.Locale

/** The languages this screen is being read in, most preferred first. */
@Composable
fun currentLocales(): List<Locale> {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { List(locales.size()) { locales[it] } }
}

/**
 * The line at the top of Home that says there is something to read.
 *
 * Deliberately the same shape as [PollBanner] and deliberately not the same colour. They sit in the
 * same place and can appear together, so they have to be told apart at a glance; a question you are
 * being asked and a notice you are being given are different things and should not look identical.
 * Primary rather than secondary, and a megaphone rather than a poll.
 */
@Composable
fun AnnouncementBanner(
    announcement: Announcement,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = announcement.textFor(currentLocales())

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = text.banner,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    // A banner is a line, and the document can hold a paragraph.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.announcement_banner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.announcement_dismiss),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * The announcement itself, taking the whole screen the way a poll does.
 *
 * It used to be an ordinary dialog, on the reasoning that it carried a paragraph and one button.
 * That stopped being true once announcements could have several pictures and buttons: every other
 * dialog here caps itself at 560dp, which made a picture a thumbnail and pushed the buttons under
 * the text. So it is laid out like [PollDialog]: a picture across the top, the words, more pictures
 * in a row, and the buttons fixed at the bottom where they are always in reach. Side by side on a
 * wide screen, pictures on one side and words and buttons on the other.
 *
 * Every button closes it, since opening it is what deals with it, and so does the cross.
 */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: () -> Unit,
) {
    val text = announcement.textFor(currentLocales())
    val uriHandler = LocalUriHandler.current
    // A picture opened full size. Saveable, so turning the phone does not close it.
    var enlarged by rememberSaveable(announcement.id) { mutableStateOf<String?>(null) }

    val onAction: (AnnouncementAction) -> Unit = { action ->
        // A phone with no browser still throws on a good link; a missed link is not worth the app.
        runCatching { uriHandler.openUri(action.url) }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                val wide = maxWidth >= 720.dp
                // A phone on its side is wide but short: buttons fixed at the bottom there left
                // the words a strip a few lines high, so they scroll with the words instead.
                val short = maxHeight < 480.dp
                val pictures = announcement.heroUrl != null || announcement.gallery.isNotEmpty()
                // Numbered together, the one at the top first, for a screen reader.
                val total = announcement.gallery.size + if (announcement.heroUrl != null) 1 else 0
                val firstInRow = if (announcement.heroUrl != null) 2 else 1

                Column(Modifier.fillMaxSize()) {
                    AnnouncementTopBar(onDismiss)

                    if (wide && pictures) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                announcement.heroUrl?.let { url ->
                                    AnnouncementHero(url, stringResource(R.string.announcement_picture, 1, total), onOpen = { enlarged = url })
                                    Spacer(Modifier.height(16.dp))
                                }
                                AnnouncementGallery(announcement.gallery, height = 200.dp, first = firstInRow, total = total, onOpen = { enlarged = it })
                                Spacer(Modifier.height(24.dp))
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    AnnouncementWords(text)
                                    Spacer(Modifier.height(16.dp))
                                    if (short) AnnouncementFooter(text.actions, onAction, onDismiss)
                                }
                                if (!short) AnnouncementFooter(text.actions, onAction, onDismiss)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .widthIn(max = 720.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp)
                        ) {
                            announcement.heroUrl?.let { url ->
                                AnnouncementHero(url, stringResource(R.string.announcement_picture, 1, total), onOpen = { enlarged = url })
                                Spacer(Modifier.height(24.dp))
                            }
                            AnnouncementWords(text)
                            if (announcement.gallery.isNotEmpty()) {
                                Spacer(Modifier.height(20.dp))
                                AnnouncementGallery(announcement.gallery, height = 220.dp, first = firstInRow, total = total, onOpen = { enlarged = it })
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp)
                        ) {
                            AnnouncementFooter(text.actions, onAction, onDismiss)
                        }
                    }
                }
            }
        }

        enlarged?.let { url -> EnlargedPicture(url, onClose = { enlarged = null }) }
    }
}

@Composable
private fun AnnouncementTopBar(onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.announcement_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, stringResource(R.string.poll_close))
        }
    }
}

@Composable
private fun AnnouncementWords(text: AnnouncementText) {
    Text(
        text = text.title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    text.body?.let {
        Spacer(Modifier.height(10.dp))
        FormattedBody(it)
    }
}

/**
 * The buttons, outside the scrolling part so they are always in reach. The first of the
 * document's buttons is the big one; with none, the big one simply closes.
 */
@Composable
private fun AnnouncementFooter(
    actions: List<AnnouncementAction>,
    onAction: (AnnouncementAction) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    ) {
        val main = actions.firstOrNull()
        Button(
            onClick = { if (main != null) onAction(main) else onClose() },
            shape = RoundedCornerShape(16.dp),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = main?.label ?: stringResource(R.string.announcement_done),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions.drop(1).forEach { action ->
            FilledTonalButton(
                onClick = { onAction(action) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (main != null) {
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.explain_close))
            }
        }
    }
}

/**
 * The picture at the top, at its own shape within reason: a wide banner stays wide, a square
 * stays square, and a tall one is trimmed to 4:5 rather than filling the screen. 16:9 until it
 * has loaded. One that cannot be loaded is left out rather than shown as an empty box.
 */
@Composable
private fun AnnouncementHero(url: String, description: String, onOpen: () -> Unit) {
    var ratio by remember(url) { mutableFloatStateOf(16f / 9f) }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) ratio = (size.width / size.height).coerceIn(0.8f, 2.4f)
            },
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
        // Keeps a bright photo from fighting the text that follows it, as on a poll.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.18f),
                    )
                )
        )
    }
}

/**
 * The other pictures, in a row that scrolls sideways, each at its own shape within reason, so a
 * phone screenshot and a wide banner can sit side by side. Tapping one opens it full size.
 */
@Composable
private fun AnnouncementGallery(urls: List<String>, height: Dp, first: Int, total: Int, onOpen: (String) -> Unit) {
    if (urls.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        itemsIndexed(urls, key = { _, url -> url }) { index, url ->
            GalleryPicture(
                url = url,
                height = height,
                description = stringResource(R.string.announcement_picture, first + index, total),
                onOpen = { onOpen(url) },
            )
        }
    }
}

@Composable
private fun GalleryPicture(url: String, height: Dp, description: String, onOpen: () -> Unit) {
    var ratio by remember(url) { mutableFloatStateOf(3f / 4f) }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return

    Box(
        modifier = Modifier
            .height(height)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) ratio = (size.width / size.height).coerceIn(0.45f, 1.8f)
            },
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** One picture on black, whole, the way a gallery app shows it. Any tap closes it. */
@Composable
private fun EnlargedPicture(url: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose)
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(8.dp)
                    // A white cross alone vanishes on a bright picture.
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.poll_close), tint = Color.White)
            }
        }
    }
}
