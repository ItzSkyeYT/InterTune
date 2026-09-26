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
 * document that said javascript: or intent: must not become something to tap, and neither must
 * the host in intent://host/x or ftp://host/x.
 *
 * A bare address has to be written in lower case, the way an address is typed, since a question
 * like "Last.fm/YouTube?" names two services rather than a page. A full address opens with its
 * scheme in lower case: autocorrect makes "Https://" of a line that starts with one, and Android
 * matches schemes exactly, so no browser would take it. Punctuation that ends a sentence or
 * closes a quote is left out of the address, and so is a closing bracket, unless the address
 * opened it, as in en.wikipedia.org/wiki/Opus_(audio_format).
 */
fun webLinks(text: String): List<Pair<IntRange, String>> =
    LINK.findAll(text).mapNotNull { match ->
        val raw = withoutTail(match.value)
        val scheme = match.groups[1]?.value
        // Nothing left after the scheme, as in "starts with https://."
        if (scheme != null && raw.length <= scheme.length + 3) return@mapNotNull null
        val range = match.range.first until match.range.first + raw.length
        val url = if (scheme != null) scheme.lowercase() + raw.substring(scheme.length) else "https://$raw"
        range to url
    }.toList()

/**
 * A full address, http or https in any case, or a bare one: lower case names joined by dots, then
 * a slash and a path. A bare one never starts straight after a dot, slash, colon, @ or hyphen,
 * where it would only be the end of something else, such as the host of ftp://files.example.com/x
 * or the last part of a name written with capitals.
 */
private val LINK = Regex(
    """\b(?i:(https?))://[^\s<>"]+|\b(?<![./:@-])(?:[a-z0-9][a-z0-9-]*\.)+[a-z]{2,}/[^\s<>"]*""",
)

/** Punctuation that ends a sentence, and closing quotes as several languages write them. */
private const val TRAILING = ".,;:!?'\"“”‘’«»‹›…。，、！？；：）」』】》"

/** [match] without what follows the address, keeping a closing bracket the address opened. */
private fun withoutTail(match: String): String {
    var s = match
    while (s.isNotEmpty()) {
        val last = s.last()
        val opening = when (last) {
            ')' -> '('
            ']' -> '['
            else -> null
        }
        val belongs = if (opening == null) last !in TRAILING
        else s.count { it == opening } >= s.count { it == last }
        if (belongs) break
        s = s.dropLast(1)
    }
    return s
}

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
