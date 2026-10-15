/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.LivingColours
import com.dd3boh.outertune.constants.LivingColoursKey
import com.dd3boh.outertune.constants.LivingSmoothingKey
import com.dd3boh.outertune.constants.LivingStrengthKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.Unreleased
import com.dd3boh.outertune.lyrics.LyricsShare
import com.dd3boh.outertune.lyrics.LyricsVideo
import com.dd3boh.outertune.lyrics.LyricsVideoPlan
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.player.LivingField
import com.dd3boh.outertune.ui.theme.extractGradientColors
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A few lines of a song's lyrics as a picture to send: the lines in large type over the colours of
 * the song's cover, the cover with the title and the artists above them, and the app's name below.
 *
 * Drawn here at a size of its own, 1080 pixels wide and as tall as the lines need, from a square to
 * the shape of a phone's screen, rather than taken from what the screen shows. So it comes out the
 * same from every phone, at a size the apps it goes to show well.
 */
object LyricsCard {
    private const val TAG = "LyricsCard"

    internal const val WIDTH = 1080
    private const val MIN_HEIGHT = 1080
    private const val MAX_HEIGHT = 1920
    internal const val MARGIN = 96f
    private const val COVER = 168f

    /** Between the header and the lines, and between the lines and the app's name. */
    internal const val GAP = 72f
    internal const val LOGO = 56f

    /** The lines start this large and come down, four pixels at a time, until they fit. */
    private const val LARGEST = 76f
    private const val SMALLEST = 44f

    /** The cover is asked for at twice the size it is drawn at, which any cover has. */
    const val COVER_PX = 336

    /** The app's own name, the same from every build: a preview's name is not one to send round. */
    private const val APP_NAME = "InterTune"

    /** How long a card stays in the cache: an app it was sent to may still be reading it a while after. */
    private const val KEPT_MS = 60L * 60 * 1000

    /** The picture of [lines] from the song called [title], by [artists], with its [cover] where there is one. */
    fun draw(context: Context, lines: List<String>, title: String, artists: String, cover: Bitmap?): Bitmap {
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val inner = (WIDTH - 2 * MARGIN).toInt()

        val head = Header(title, artists, cover)
        val header = head.height

        // The lines as large as the card lets them be, each a paragraph of its own.
        val room = MAX_HEIGHT - (MARGIN + header + GAP + GAP + LOGO + MARGIN)
        var size = LARGEST
        var body = lineLayouts(lines, size, bold, inner)
        while (bodyHeight(body, size) > room && size > SMALLEST) {
            size -= 4f
            body = lineLayouts(lines, size, bold, inner)
        }
        val height = (MARGIN + header + GAP + bodyHeight(body, size) + GAP + LOGO + MARGIN).roundToInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)

        val card = createBitmap(WIDTH, height)
        val canvas = Canvas(card)
        val (top, bottom) = colours(cover)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, WIDTH * 0.4f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })

        head.draw(canvas, MARGIN)

        // In the middle of what is left between the header and the app's name. A song whose five
        // lines are each a verse long still does not fit at the smallest size: it is cut there.
        val bodyTop = MARGIN + header + GAP
        val bodyRoom = height - MARGIN - LOGO - GAP - bodyTop
        var at = bodyTop + max(0f, (bodyRoom - bodyHeight(body, size)) / 2)
        canvas.withClip(0f, bodyTop, WIDTH.toFloat(), bodyTop + bodyRoom) {
            for (line in body) {
                withTranslation(MARGIN, at) { line.draw(this) }
                at += line.height + paragraphGap(size)
            }
        }

        drawMark(context, canvas, MARGIN, height - MARGIN - LOGO)
        return card
    }

    /**
     * The cover with the title and the artists beside it, or the two across the width where there
     * is no cover: the top of a card, and of a video ([LyricsVideoFrames]). [height] tall.
     */
    internal class Header(title: String, artists: String, private val cover: Bitmap?) {
        private val wordsLeft = if (cover != null) MARGIN + COVER + 36f else MARGIN
        private val wordsWidth = (WIDTH - MARGIN - wordsLeft).toInt()
        private val titleLayout = layout(title, paint(46f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD)), wordsWidth, maxLines = 2)
        private val artistsLayout = artists.takeIf { it.isNotBlank() }
            ?.let { layout(it, paint(36f, Typeface.DEFAULT, alpha = 0.75f), wordsWidth, maxLines = 2) }
        private val words = titleLayout.height + (artistsLayout?.let { 8 + it.height } ?: 0)
        val height = max(if (cover != null) COVER else 0f, words.toFloat())

        /** Drawn from [top] down, [MARGIN] in from the left. */
        fun draw(canvas: Canvas, top: Float) {
            cover?.let { drawCover(canvas, it, RectF(MARGIN, top + (height - COVER) / 2, MARGIN + COVER, top + (height + COVER) / 2)) }
            val wordsTop = top + (height - words) / 2
            canvas.withTranslation(wordsLeft, wordsTop) { titleLayout.draw(this) }
            artistsLayout?.let { canvas.withTranslation(wordsLeft, wordsTop + titleLayout.height + 8) { it.draw(this) } }
        }
    }

    /** The app's mark and its name after it, [LOGO] tall, from [left] and [top]. */
    internal fun drawMark(context: Context, canvas: Canvas, left: Float, top: Float) {
        ContextCompat.getDrawable(context, R.drawable.small_icon)?.mutate()?.let { mark ->
            mark.setTint(Color.WHITE)
            mark.setBounds(left.toInt(), top.toInt(), (left + LOGO).toInt(), (top + LOGO).toInt())
            mark.draw(canvas)
        }
        val name = paint(40f, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), alpha = 0.9f)
        canvas.drawText(APP_NAME, left + LOGO + 16f, top + LOGO / 2 - (name.descent() + name.ascent()) / 2, name)
    }

    /**
     * Saves [card] where the app's FileProvider hands it out from and opens the share sheet with it.
     * False when it could not be saved: a full disk, or nothing to read it with.
     */
    suspend fun share(context: Context, card: Bitmap, title: String): Boolean = runCatching {
        val file = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "lyrics-cards")
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEPT_MS }?.forEach { it.deleteRecursively() }
            // A folder of its own each time, so that the file keeps the song's name and a second
            // card of the same song is never written over one still being read.
            val own = File(folder, System.currentTimeMillis().toString()).apply { mkdirs() }
            File(own, LyricsShare.fileName(title)).also { file ->
                file.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The share sheet's preview, and the right to read the file for whichever app is picked.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }.onFailure { Log.w(TAG, "Could not share the lyrics card", it) }.isSuccess

    /** The two colours the player's gradient takes from the cover, darkened until white words read on them. */
    internal fun colours(cover: Bitmap?): Pair<Int, Int> {
        val two = cover?.let { runCatching { it.extractGradientColors() }.getOrNull() }?.map { it.toArgb() }
            ?: listOf(0xFF595959.toInt(), 0xFF0D0D0D.toInt())
        val first = two.first()
        val second = two.getOrElse(1) { first }
        return readable(first) to readable(ColorUtils.blendARGB(second, Color.BLACK, 0.3f))
    }

    /** [color] made darker, a step at a time, until white text on it has the contrast body text asks for. */
    private fun readable(color: Int): Int {
        var c = color or 0xFF000000.toInt()
        repeat(16) {
            if (ColorUtils.calculateContrast(Color.WHITE, c) >= 4.5) return c
            c = ColorUtils.blendARGB(c, Color.BLACK, 0.12f)
        }
        return c
    }

    /** The cover, cut square from its middle and rounded, through a shader: a clipped path has jagged corners here. */
    private fun drawCover(canvas: Canvas, cover: Bitmap, into: RectF) {
        val side = minOf(cover.width, cover.height).toFloat()
        val from = RectF((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2)
        val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setRectToRect(from, into, Matrix.ScaleToFit.FILL) })
        }
        canvas.drawRoundRect(into, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
    }

    private fun lineLayouts(lines: List<String>, size: Float, face: Typeface, width: Int): List<StaticLayout> =
        lines.map { layout(it, paint(size, face), width, spacing = 1.1f) }

    /** The lines' height together, with the room between them that tells one line from the next where a long one wraps. */
    private fun bodyHeight(lines: List<StaticLayout>, size: Float): Float =
        lines.sumOf { it.height }.toFloat() + paragraphGap(size) * (lines.size - 1).coerceAtLeast(0)

    private fun paragraphGap(size: Float) = size * 0.45f

    internal fun paint(size: Float, face: Typeface, alpha: Float = 1f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.alpha = (alpha * 255).roundToInt()
        textSize = size
        typeface = face
    }

    internal fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int = Int.MAX_VALUE, spacing: Float = 1f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}

/**
 * The picture of [lines] as it will be sent, with Share under it, or with synced lyrics the video
 * of them ([video]), seen as a still of it before it is made. Shown before it goes, since it goes
 * to somebody else: a line picked by mistake is seen here and not after.
 *
 * A picture takes up to [LyricsShare.MOST_LINES] lines, so more than that is a video or nothing.
 */
@Composable
fun LyricsCardDialog(lines: List<String>, song: MediaMetadata, video: LyricsVideoPlan.Clip?, onDismiss: () -> Unit, onShared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerConnection = LocalPlayerConnection.current
    val canPicture = lines.size <= LyricsShare.MOST_LINES
    var asVideo by remember(lines, video) { mutableStateOf(!canPicture && video != null) }
    var card by remember(lines, song.id) { mutableStateOf<Bitmap?>(null) }
    var still by remember(video, song.id) { mutableStateOf<Bitmap?>(null) }
    var making by remember { mutableStateOf<Job?>(null) }
    var made by remember { mutableFloatStateOf(0f) }

    // Living blur behind the video where it is the player's background, as the player has it.
    val background by rememberEnumPreference(PlayerBackgroundStyleKey, DEFAULT_PLAYER_BACKGROUND)
    val livingStrength by rememberPreference(LivingStrengthKey, LivingField.DEFAULT_STRENGTH)
    val livingSmoothing by rememberPreference(LivingSmoothingKey, LivingField.DEFAULT_SMOOTHING)
    val livingColours by rememberEnumPreference(LivingColoursKey, LivingColours.MAIN)
    val living = background == PlayerBackgroundStyle.LIVING && Unreleased.LIVING_BACKGROUND
    var cover by remember(song.id) { mutableStateOf<Bitmap?>(null) }
    var livingFrom by remember(song.id, livingColours) { mutableStateOf<Pair<IntArray?, IntArray?>?>(null) }

    /** The video's frames, moving with what was [heard] of the song, or still where nothing was. */
    fun frames(clip: LyricsVideoPlan.Clip, heard: LyricsVideo.Heard?) = LyricsVideoFrames(
        context, clip, song.title, song.artists.joinToString { it.name }, cover,
        living = livingFrom?.takeIf { living }?.let { (patches, mains) ->
            LivingVideo(livingColours, patches, mains, livingStrength, livingSmoothing) { heard?.at(it) }
        },
    )

    LaunchedEffect(lines, song.id, video, living, livingColours) {
        // The cover the player shows, which Coil has already, at a size of the card's own.
        cover = loadCover(context, song.getThumbnailModel(LyricsCard.COVER_PX, LyricsCard.COVER_PX))
        // and the small one Living blur takes its colours from, as the player does
        if (living && video != null) {
            livingFrom = loadCover(context, song.getThumbnailModel(100, 100))?.let { small ->
                withContext(Dispatchers.Default) { livingColoursOf(small, livingColours) }
            }
        }
        if (canPicture) card = withContext(Dispatchers.Default) {
            LyricsCard.draw(context, lines, song.title, song.artists.joinToString { it.name }, cover)
        }
        if (video != null) still = withContext(Dispatchers.Default) {
            createBitmap(LyricsVideoFrames.WIDTH, LyricsVideoFrames.HEIGHT).also {
                // its first frame, which is what a chat shows of it before it plays
                frames(video, null).draw(Canvas(it), 0)
            }
        }
    }

    fun makeVideo(clip: LyricsVideoPlan.Clip) {
        val service = playerConnection?.service ?: return
        made = 0f
        // A turn of the phone makes the screen anew, and the video in the making went with the old
        // one: the screen stays the way it is turned until the video is made.
        val activity = context.activity()
        val turned = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        making = scope.launch {
            try {
                val file = LyricsVideo.make(
                    context, service.songSource(), song.id, clip, song.title,
                    frames = { heard -> frames(clip, heard) },
                    progress = { made = it },
                )
                // Not over a locked phone or another app: it waits for the screen to be back.
                lifecycleOwner.withResumed {
                    if (LyricsVideo.share(context, file, song.title)) {
                        // how big it is, to know before sending it where files have a limit
                        val size = Formatter.formatFileSize(context, file.length())
                        Toast.makeText(context, context.getString(R.string.lyrics_video_size, size), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                    }
                }
                onShared()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w("LyricsCard", "The lyrics video could not be made", failure)
                Toast.makeText(context, R.string.lyrics_video_failed, Toast.LENGTH_LONG).show()
            } finally {
                making = null
                if (activity != null && turned != null) activity.requestedOrientation = turned
            }
        }
    }

    DefaultDialog(
        // A tap beside the dialog does not throw away a video half made: Cancel does.
        onDismiss = { if (making == null) onDismiss() },
        buttons = {
            TextButton(onClick = { making?.cancel() ?: onDismiss() }) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = making == null && (if (asVideo) still != null else card != null),
                onClick = {
                    if (asVideo) {
                        video?.let(::makeVideo)
                        return@TextButton
                    }
                    val ready = card ?: return@TextButton
                    scope.launch {
                        if (!LyricsCard.share(context, ready, song.title)) {
                            Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
                        }
                        onShared()
                    }
                }
            ) {
                Text(stringResource(R.string.share))
            }
        }
    ) {
        if (canPicture && video != null) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(bottom = 12.dp)) {
                SegmentedButton(
                    selected = !asVideo,
                    onClick = { asVideo = false },
                    enabled = making == null,
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.lyrics_as_picture)) }
                SegmentedButton(
                    selected = asVideo,
                    onClick = { asVideo = true },
                    enabled = making == null,
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.lyrics_as_video)) }
            }
        }
        val picture = if (asVideo) still else card
        if (picture == null) {
            Box(
                Modifier.weight(1f, fill = false).fillMaxWidth().aspectRatio(if (asVideo) 9f / 16f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = remember(picture) { picture.asImageBitmap() },
                contentDescription = lines.joinToString("\n"),
                contentScale = ContentScale.Fit,
                // Shrinks to leave the buttons room on a short screen, keeping its shape.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        if (asVideo && video != null) {
            Text(
                text = if (making != null) stringResource(R.string.lyrics_video_making)
                else stringResource(R.string.lyrics_video_length, makeTimeString(video.durationMs)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (making != null) {
                // as wide as the words over it, about: the dialog keeps its width when it comes
                LinearProgressIndicator(
                    progress = { made },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .width(200.dp),
                )
            }
        }
    }
}

/** The activity this context is, or wraps. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The cover as a bitmap the drawing can read, from what Coil is given for it, or null for none. */
private suspend fun loadCover(context: Context, model: Any?): Bitmap? = withContext(coilCoroutine) {
    runCatching {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .allowHardware(false)
                .build()
        ).image?.toBitmap()
    }.getOrNull()
}

/**
 * The bar the lyrics view shows while lines are picked for a picture, where its own buttons were:
 * how many are picked, out of how many a picture takes, or how long their video is ([videoMs])
 * where they make one, Share, and a cross that stops the picking.
 */
@Composable
fun LyricsPickBar(picked: Int, videoMs: Long?, onCancel: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.lyrics_pick_cancel))
            }
            Text(
                text = when {
                    picked == 0 -> stringResource(R.string.lyrics_pick_hint)
                    videoMs != null -> pluralStringResource(R.plurals.lyrics_picked_video, picked, picked, makeTimeString(videoMs))
                    else -> pluralStringResource(R.plurals.lyrics_picked, picked, picked, LyricsShare.MOST_LINES)
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onShare,
                enabled = picked > 0,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.share))
            }
        }
    }
}
