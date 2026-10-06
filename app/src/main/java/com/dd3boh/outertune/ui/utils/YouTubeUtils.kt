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
 * Granularity for artwork requests, in pixels.
 *
 * The requested size ends up in the url, and the url is the cache key, so every distinct width is a
 * separate fetch, disk entry and decode.
 *
 * 256 rather than something finer, because the point is to make the common pair collide. A device
 * measuring 1152 in portrait and 1248 in landscape still lands on two different buckets at 64 or
 * 128; at 256 both round to 1280 and a rotation reuses what portrait already fetched. The cost is
 * up to 255px of over-fetch on one axis, which is cheaper than a second copy of the whole cover.
 *
 * The full-size player artwork goes through this, and so does the cover handed to the system
 * (sessionArtwork): on a phone 1080 pixels wide the system keeps 900 of them and the player draws
 * its cover 900 wide, so both ask for 1024 and whichever comes second finds it on disk. The mini
 * player and the palette source ask for their own much smaller sizes and are better off unrounded.
 */
private const val ART_SIZE_BUCKET = 256

/** [px] rounded up to the next size a whole cover is asked for at. */
fun artSizeBucket(px: Int): Int = ((px + ART_SIZE_BUCKET - 1) / ART_SIZE_BUCKET) * ART_SIZE_BUCKET

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
