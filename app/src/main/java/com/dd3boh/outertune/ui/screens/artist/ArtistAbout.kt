/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.utils.joinByBullet
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.DescriptionPart

/** Lines of the description shown before More, enough to say who the artist is. */
const val ArtistAboutLines = 3

/** Whether the page has anything for the About section: a description or one of the counts. */
fun hasAbout(page: ArtistPage?): Boolean =
    page != null && (page.description != null || page.subscriberCount != null || page.monthlyListeners != null)

/**
 * The number that picks the plural for a subscriber count YouTube wrote as text.
 *
 * A count written out in full ("523", "1 204") is that number. A short one ("7.19M", "12K",
 * "7,19 M") has a letter for its size and is far above one: a thousand for K, a million for the
 * rest, so that French says "7,19 M d'abonnés", as it does of a million, and "12 k abonnés".
 */
fun subscriberQuantity(count: String): Int {
    val letters = count.filter { it.isLetter() }
    if (letters.isEmpty()) {
        val digits = count.filter { it.isDigit() }
        return digits.take(9).toIntOrNull() ?: 0
    }
    return if (letters.equals("k", ignoreCase = true)) 1_000 else 1_000_000
}

/**
 * The artist's description, subscribers and monthly audience, as YouTube Music gives them. Only
 * [ArtistAboutLines] lines of the description show at first, since the songs come after it; More
 * opens the rest where it stands and Less closes it again.
 */
@Composable
fun ArtistAbout(
    page: ArtistPage,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(page.artist.id) { mutableStateOf(false) }
    var overflows by remember(page.artist.id) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        NavigationTitle(title = stringResource(R.string.about))

        val subscribers = page.subscriberCount?.let {
            pluralStringResource(R.plurals.artist_subscribers, subscriberQuantity(it), it)
        }
        val facts = joinByBullet(page.monthlyListeners, subscribers)
        if (facts.isNotEmpty()) {
            Text(
                text = facts,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        if (page.description != null) {
            Text(
                text = rememberDescription(page.descriptionParts),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else ArtistAboutLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = if (facts.isNotEmpty()) 8.dp else 0.dp)
                    .animateContentSize()
            )
            if (overflows || expanded) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(stringResource(if (expanded) R.string.artist_about_less else R.string.artist_about_more))
                }
            }
        }
    }
}

/**
 * The description with its links tappable. Only web links are: YouTube's are to Wikipedia and to
 * the licence, and anything else in a text from outside the app stays plain text.
 */
@Composable
private fun rememberDescription(parts: List<DescriptionPart>): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(parts, linkColor) {
        buildAnnotatedString {
            // The last piece may end with a line break, which would leave an empty line.
            val last = parts.lastIndex
            parts.forEachIndexed { index, part ->
                val text = if (index == last) part.text.trimEnd() else part.text
                val url = part.url
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            // Wrapped, since a phone with nothing that opens the address would
                            // otherwise throw on the tap.
                            linkInteractionListener = { runCatching { uriHandler.openUri(url) } },
                        )
                    ) { append(text) }
                } else {
                    append(text)
                }
            }
        }
    }
}
