package com.dd3boh.outertune.ui.utils

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    "https://lh3\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*".toRegex().matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        // Multiply before dividing. These are Ints, so (w / W) * H truncates to zero whenever the
        // requested size is smaller than the source's, and collapses to the source's own dimension
        // when it is larger - either way the aspect ratio is lost.
        if (w != null && h == null) h = w * H / W
        if (w == null && h != null) w = h * W / H
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()) {
        return "$this-s${width ?: height}"
    }
    return this
}