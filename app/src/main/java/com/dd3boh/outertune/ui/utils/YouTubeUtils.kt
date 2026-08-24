package com.dd3boh.outertune.ui.utils

/**
 * Matches any googleusercontent host, capturing the size so only that part is rewritten.
 *
 * The host matters. This used to accept `lh3.googleusercontent.com` only, but YouTube Music serves
 * artwork from `yt3.googleusercontent.com`, so the pattern matched nothing and [resize] silently
 * returned the url untouched. Measured against a real library, that was 30630 of 31783 songs, and
 * many of them are stored at `=w120-h120`: a 120 pixel image stretched across a 1200 pixel player.
 *
 * The tail is captured rather than rebuilt because these urls carry flags that are not ours to
 * invent, for example `-s-l90-rj` versus `-l90-rj`. Only the numbers change.
 */
private val GOOGLE_ART_SIZE =
    "^(https://[a-z0-9]+\\.googleusercontent\\.com/.*?=w)(\\d+)(-h)(\\d+)(.*)$".toRegex()

/** Legacy host, sized with a single `=sNNN` rather than width and height. */
private val GGPHT_ART_SIZE = "^https://yt3\\.ggpht\\.com/.*=s(\\d+)$".toRegex()

/**
 * Asks the CDN for artwork at the size it is actually going to be drawn at.
 *
 * Passing only one dimension derives the other from the source's aspect ratio.
 */
fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this

    GOOGLE_ART_SIZE.matchEntire(this)?.let { match ->
        val (prefix, sourceW, separator, sourceH, suffix) = match.destructured
        val W = sourceW.toInt()
        val H = sourceH.toInt()
        if (W <= 0 || H <= 0) return this

        var w = width
        var h = height
        // Multiply before dividing. These are Ints, so (w / W) * H truncates to zero whenever the
        // requested size is smaller than the source's, and collapses to the source's own dimension
        // when it is larger - either way the aspect ratio is lost.
        if (w != null && h == null) h = w * H / W
        if (w == null && h != null) w = h * W / H

        return "$prefix$w$separator$h$suffix"
    }

    GGPHT_ART_SIZE.matchEntire(this)?.let {
        return "$this-s${width ?: height}"
    }

    return this
}
