/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * The web addresses in [text], each with the range it covers and the address to open.
 *
 * Poll and announcement text is written by hand in a document, and an address in it could only be
 * read, not tapped: the Discord invite had to be typed into a browser. Both full addresses and
 * bare ones like discord.gg/abc are found, a bare one opened as https. Nothing else is: a
 * document that said javascript: or intent: must not become something to tap. Punctuation that
 * ends a sentence is left out of the address.
 */
fun webLinks(text: String): List<Pair<IntRange, String>> =
    LINK.findAll(text).mapNotNull { match ->
        val raw = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '"', '\'')
        if (raw.isEmpty()) return@mapNotNull null
        val range = match.range.first until match.range.first + raw.length
        val url = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw
        else "https://$raw"
        range to url
    }.toList()

private val LINK = Regex(
    """(?i)\bhttps?://[^\s<>"]+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s<>"]*""",
)

/** [text] with its web addresses tappable, opening in the browser or the app that owns them. */
@Composable
fun rememberLinkified(text: String): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        val links = webLinks(text)
        buildAnnotatedString {
            var at = 0
            for ((range, url) in links) {
                append(text.substring(at, range.first))
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        // Wrapped, since a phone with nothing that opens the address would
                        // otherwise throw on the tap.
                        linkInteractionListener = { runCatching { uriHandler.openUri(url) } },
                    )
                ) { append(text.substring(range)) }
                at = range.last + 1
            }
            append(text.substring(at))
        }
    }
}
