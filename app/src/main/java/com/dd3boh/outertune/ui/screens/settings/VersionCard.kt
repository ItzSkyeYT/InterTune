/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.VersionCardFoundKey
import com.dd3boh.outertune.constants.VersionCardIdKey
import com.dd3boh.outertune.lyrics.LyricsSave
import com.dd3boh.outertune.ui.component.LyricsCard
import com.dd3boh.outertune.ui.utils.Animations
import com.dd3boh.outertune.utils.LocaleDateFormat
import com.dd3boh.outertune.utils.Toaster
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.editOrSay
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * This device's card ([DeviceCard]), drawn, and the screen that shows it: About's version line
 * tapped seven times, quickly, as Android's build number is ([rememberVersionTap]), or once after
 * it was found.
 */
object VersionCard {
    /** Android's id of this device for this app, as old Android 2.2 phones all gave it: worth nothing. */
    private const val SHARED_ID = "9774d56d682e549c"

    /** The card of this device. */
    fun design(context: Context): DeviceCard.Design = DeviceCard.design(deviceId(context))

    /**
     * What the card is drawn from: Android's id for this device and this app's signing key, which
     * stays through a reinstall, and a random one kept in the settings where Android has none. Only
     * ever hashed ([DeviceCard.digest]): never shown, kept, logged or sent as it is.
     */
    @SuppressLint("HardwareIds")
    private fun deviceId(context: Context): String {
        val android = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        if (!android.isNullOrBlank() && android != SHARED_ID) return android
        context.dataStore[VersionCardIdKey]?.takeIf { it.isNotBlank() }?.let { return it }
        val made = UUID.randomUUID().toString()
        CoroutineScope(Dispatchers.IO).launch(NonCancellable) {
            context.editOrSay { if (it[VersionCardIdKey].isNullOrBlank()) it[VersionCardIdKey] = made }
        }
        return made
    }

    /** Its name as a file, and as the share sheet shows it. */
    fun title(design: DeviceCard.Design): String = "InterTune " + design.serial.removePrefix("No. ")

    /**
     * The card as a picture, [DeviceCard.WIDTH] by [DeviceCard.HEIGHT], its corners cut round and
     * clear: the art, then the band at its foot with the app's mark and name, the serial, and the
     * day it was [found] ("Found 7 Nov 2026"). The same picture is shown, saved and shared.
     */
    fun draw(context: Context, design: DeviceCard.Design, found: String): Bitmap {
        val width = DeviceCard.WIDTH.toFloat()
        val height = DeviceCard.HEIGHT.toFloat()
        val art = (DeviceCard.ART * width).toFloat()
        val palette = design.palette

        val flat = createBitmap(DeviceCard.WIDTH, DeviceCard.HEIGHT)
        val canvas = Canvas(flat)
        canvas.drawRect(0f, 0f, width, art, Paint().apply {
            shader = LinearGradient(0f, 0f, if (design.diagonal) width else 0f, art, palette.paper, design.tint, Shader.TileMode.CLAMP)
        })
        canvas.withClip(0f, 0f, width, art) {
            // Laid on as inks are printed, each darkening what it covers; painted on dark paper.
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            if (!palette.dark) fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            for (circle in design.circles) {
                fill.color = circle.colour
                if (palette.dark) fill.alpha = 235
                drawCircle((circle.x * width).toFloat(), (circle.y * width).toFloat(), (circle.r * width).toFloat(), fill)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            for (arc in design.arcs) {
                line.color = arc.colour
                line.strokeWidth = (arc.stroke * width).toFloat()
                val box = RectF(
                    ((arc.cx - arc.radius) * width).toFloat(), ((arc.cy - arc.radius) * width).toFloat(),
                    ((arc.cx + arc.radius) * width).toFloat(), ((arc.cy + arc.radius) * width).toFloat(),
                )
                drawArc(box, arc.start.toFloat(), arc.sweep.toFloat(), false, line)
            }
        }

        // The band: the mark and the name, the day under the name, the serial at the right.
        canvas.drawRect(0f, art, width, height, Paint().apply { color = palette.ink })
        val pad = 0.056f * width
        val mark = 0.056f * width
        val name = text(0.044f * width, bold = true, alpha = 1f)
        val day = text(0.029f * width, bold = false, alpha = 0.66f)
        val number = text(0.036f * width, bold = true, alpha = 0.94f).apply {
            letterSpacing = 0.04f
            fontFeatureSettings = "tnum"
        }
        val numberLead = text(0.036f * width, bold = false, alpha = 0.6f).apply { letterSpacing = 0.04f }
        val nameCap = capHeight(name)
        val block = nameCap + 0.05f * width + capHeight(day)
        val nameBaseline = art + (height - art - block) / 2 + nameCap
        val dayBaseline = nameBaseline + 0.05f * width + capHeight(day)
        ContextCompat.getDrawable(context, R.drawable.small_icon)?.mutate()?.let { icon ->
            val top = nameBaseline - nameCap / 2 - mark / 2
            icon.setBounds(pad.toInt(), top.toInt(), (pad + mark).toInt(), (top + mark).toInt())
            icon.draw(canvas)
        }
        val wordsLeft = pad + mark + 0.024f * width
        canvas.drawText("InterTune", wordsLeft, nameBaseline, name)
        canvas.drawText(found, wordsLeft, dayBaseline, day)
        val code = design.serial.removePrefix("No. ")
        val codeLeft = width - pad - number.measureText(code)
        canvas.drawText(code, codeLeft, nameBaseline, number)
        canvas.drawText("No. ", codeLeft - numberLead.measureText("No. "), nameBaseline, numberLead)

        // Round corners through a shader: a clipped path has jagged corners in a bitmap's canvas.
        val card = createBitmap(DeviceCard.WIDTH, DeviceCard.HEIGHT)
        val corner = (DeviceCard.CORNER * width).toFloat()
        Canvas(card).drawRoundRect(0f, 0f, width, height, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        flat.recycle()
        return card
    }

    private fun text(size: Float, bold: Boolean, alpha: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.alpha = (alpha * 255).toInt()
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun capHeight(paint: Paint): Float {
        val bounds = android.graphics.Rect()
        paint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }
}

/**
 * What a tap on About's version line does: counted by [VersionTaps] until the seventh, which keeps
 * the day the card was found and opens it ([onCard]); "3 more", "2 more", "1 more" said on the way,
 * as Android's build number says it. Once found on this device, one tap opens it.
 */
@Composable
fun rememberVersionTap(onCard: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val code = remember { VersionCard.design(context).code }
    val found = rememberPreference(VersionCardFoundKey, "")
    val open = rememberUpdatedState(onCard)
    val taps = remember { VersionTaps() }
    return remember(code) {
        {
            if (DeviceCard.foundOn(found.value, code) != null) open.value()
            else when (val answer = taps.tap(SystemClock.uptimeMillis())) {
                is VersionTaps.Answer.Left -> Toaster.show(context, resources.getString(R.string.version_card_more, answer.taps))
                VersionTaps.Answer.Card -> {
                    // "1 more" is over once the card is there.
                    Toaster.cancel()
                    found.value = DeviceCard.foundValue(code, LocalDate.now())
                    open.value()
                }
                VersionTaps.Answer.Quiet -> Unit
            }
        }
    }
}

/**
 * The card, shown: the screen dims, the card turns in from its edge with a little overshoot and a
 * tap of the phone's vibration as it lands, then a soft band of light lies across it and follows
 * the phone as it is tilted. Without a sensor for that the light crosses it once; with animations
 * off it is simply there. Save and Share under it; a tap beside it or Back closes it.
 */
@Composable
fun VersionCardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val design = remember { VersionCard.design(context) }
    val foundValue by rememberPreference(VersionCardFoundKey, "")
    // Today while the seventh tap's write is on its way.
    val foundDate = remember(foundValue, design) { DeviceCard.foundOn(foundValue, design.code) ?: LocalDate.now() }
    val day = remember(foundDate) { LocaleDateFormat(Locale.getDefault(), "yMMMd", "d MMM yyyy").format(foundDate) }
    val found = stringResource(R.string.version_card_found, day)
    val card by produceState<Bitmap?>(null, design, found) {
        value = withContext(Dispatchers.Default) { VersionCard.draw(context, design, found) }
    }

    fun saidSaved(saved: Boolean, place: String) {
        if (saved) Toaster.show(context, resources.getString(R.string.lyrics_saved, place), Toast.LENGTH_LONG)
        else Toaster.show(context, R.string.error_unknown, Toast.LENGTH_SHORT)
    }

    // Older Android asks where it goes, on the system's own save screen, as the lyrics card does.
    val pickPlace = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LyricsSave.TYPE_PICTURE)) { uri ->
        val ready = card
        if (uri != null && ready != null) scope.launch {
            saidSaved(LyricsSave.intoPicked(context, uri) { ready.compress(Bitmap.CompressFormat.PNG, 100, it) }, VersionCard.title(design))
        }
    }

    fun save() {
        val ready = card ?: return
        val name = VersionCard.title(design) + ".png"
        if (!LyricsSave.straight) {
            pickPlace.launch(name)
            return
        }
        scope.launch {
            val saved = LyricsSave.intoGallery(context, name, video = false) { ready.compress(Bitmap.CompressFormat.PNG, 100, it) }
            saidSaved(saved, LyricsSave.place(video = false))
        }
    }

    fun share() {
        val ready = card ?: return
        scope.launch {
            if (!LyricsCard.share(context, ready, VersionCard.title(design))) Toaster.show(context, R.string.error_unknown, Toast.LENGTH_SHORT)
        }
    }

    val still = Animations.off
    val dim = remember { Animatable(0f) }
    val gone = remember { Animatable(0f) }
    val turn = remember { Animatable(90f) }
    val after = remember { Animatable(0f) }
    // The light's place along the card's diagonal: -1 its top left corner, 1 its bottom right.
    val sweep = remember { Animatable(-1.9f) }
    var landed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val tilt = rememberTilt(enabled = landed && !still)

    LaunchedEffect(Unit) { dim.animateTo(1f, tween(320)) }
    LaunchedEffect(card != null) {
        if (card == null) return@LaunchedEffect
        delay(80)
        turn.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 170f)) {
            if (!landed && value <= 0f) {
                landed = true
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
        if (!landed) {
            landed = true
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    LaunchedEffect(landed) {
        if (!landed) return@LaunchedEffect
        launch { after.animateTo(1f, tween(260)) }
        if (still) return@LaunchedEffect
        // In from the top left to where the phone's tilt puts it; once across where nothing tilts it.
        if (tilt.available) sweep.animateTo(0f, tween(1300, easing = FastOutSlowInEasing))
        else sweep.animateTo(1.9f, tween(2600, easing = LinearOutSlowInEasing))
    }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            gone.animateTo(1f, tween(180))
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::leave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The dimming is the screen's own, so it can come in with the card; the bars' icons light on it.
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
            onDispose { }
        }
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - gone.value }
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.86f * dim.value)) }
                .clickable(interactionSource = null, indication = null, onClick = ::leave)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        ) {
            val wide = maxWidth > maxHeight
            val title: @Composable () -> Unit = {
                Text(
                    text = stringResource(R.string.version_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.86f),
                    modifier = Modifier.graphicsLayer { alpha = after.value },
                )
            }
            val buttons: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.graphicsLayer { alpha = after.value },
                ) {
                    FilledTonalButton(onClick = ::save, enabled = card != null, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.save))
                    }
                    Button(onClick = ::share, enabled = card != null, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.share))
                    }
                }
            }
            val ratio = (DeviceCard.ART + DeviceCard.BAND).toFloat()
            val description = stringResource(R.string.version_card_title) + ", " + design.serial + ", " + found
            if (wide) {
                // On its side: the card as tall as the screen lets it be, the rest beside it.
                val cardWidth = min((maxHeight - 8.dp) / ratio, 360.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    ShownCard(card, cardWidth, ratio, turn, sweep, tilt, still, description)
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        title()
                        buttons()
                    }
                }
            } else {
                val cardWidth = min(min(maxWidth - 40.dp, 340.dp), (maxHeight - 132.dp) / ratio)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    title()
                    Spacer(Modifier.height(16.dp))
                    ShownCard(card, cardWidth, ratio, turn, sweep, tilt, still, description)
                    Spacer(Modifier.height(24.dp))
                    buttons()
                }
            }
        }
    }
}

/** The card turned by [turn] and lit by the band of light, at [width] and [ratio] times as tall. */
@Composable
private fun ShownCard(
    card: Bitmap?,
    width: Dp,
    ratio: Float,
    turn: Animatable<Float, *>,
    sweep: Animatable<Float, *>,
    tilt: Tilt,
    still: Boolean,
    description: String,
) {
    Box(
        Modifier
            .size(width, width * ratio)
            // A tap on the card is not a tap beside it.
            .pointerInput(Unit) { detectTapGestures { } }
            .graphicsLayer {
                // It leans a few degrees with the phone, as a card in the hand does.
                rotationY = turn.value + tilt.x * 4f
                rotationX = -tilt.y * 4f
                val scale = 1f - 0.08f * (abs(turn.value) / 90f)
                scaleX = scale
                scaleY = scale
                cameraDistance = 12f * density
            }
            .drawWithCache {
                val shape = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.width * DeviceCard.CORNER.toFloat())))
                }
                onDrawWithContent {
                    drawContent()
                    if (!still && card != null) clipPath(shape) { shine(sweep.value + tilt.along) }
                }
            },
    ) {
        if (card != null) {
            Image(
                bitmap = remember(card) { card.asImageBitmap() },
                contentDescription = description,
                contentScale = ContentScale.FillBounds,
                // drawn at about two thirds of its size: smoothly, so the thin arcs stay whole
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The band of light, across the card from corner to corner, centred [at] along the diagonal (-1
 * the top left, 1 the bottom right): white in its middle, a breath of blue and pink at its edges,
 * as the foil of a holographic card.
 */
private fun DrawScope.shine(at: Float) {
    if (at < -1.9f || at > 1.9f) return
    val diagonal = sqrt(size.width * size.width + size.height * size.height)
    val along = Offset(size.width / diagonal, size.height / diagonal)
    val centre = Offset(size.width / 2, size.height / 2) + along * (at * diagonal / 2)
    val reach = diagonal * 0.2f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.32f to Color(0xFF9EE7FF).copy(alpha = 0.10f),
            0.5f to Color.White.copy(alpha = 0.28f),
            0.68f to Color(0xFFFFB9E6).copy(alpha = 0.10f),
            1f to Color.Transparent,
            start = centre - along * reach,
            end = centre + along * reach,
        )
    )
}

/**
 * How the phone is tilted from where it was held when the card landed, -1 to 1 each way: [x] to
 * the right, [y] towards the person, and [along] the two as one, along the card's diagonal. From
 * the gravity sensor, or the accelerometer smoothed, while the screen is in front; nothing is
 * listened to once the card closes or the app goes to the background.
 */
private class Tilt(val available: Boolean) {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    val along: Float get() = (x - y).coerceIn(-1f, 1f)
}

@Composable
private fun rememberTilt(enabled: Boolean): Tilt {
    val context = LocalContext.current
    val sensors = remember { context.getSystemService<SensorManager>() }
    val sensor = remember {
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    val tilt = remember { Tilt(available = sensor != null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(enabled, lifecycle) {
        if (!enabled || sensors == null || sensor == null) return@DisposableEffect onDispose { }
        val smooth = if (sensor.type == Sensor.TYPE_GRAVITY) 0.3f else 0.12f
        val listener = object : SensorEventListener {
            private var gx = Float.NaN
            private var gy = 0f
            private var restX = 0f
            private var restY = 0f

            override fun onSensorChanged(event: SensorEvent) {
                val ax = event.values[0]
                val ay = event.values[1]
                if (gx.isNaN()) {
                    gx = ax; gy = ay; restX = ax; restY = ay
                } else {
                    gx += (ax - gx) * smooth
                    gy += (ay - gy) * smooth
                    // Where it is held drifts back to the middle over some seconds.
                    restX += (gx - restX) * 0.004f
                    restY += (gy - restY) * 0.004f
                }
                // About 25 degrees each way spans the card.
                tilt.x = (-(gx - restX) / SensorManager.GRAVITY_EARTH * 2.3f).coerceIn(-1f, 1f)
                tilt.y = ((gy - restY) / SensorManager.GRAVITY_EARTH * 2.3f).coerceIn(-1f, 1f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var listening = false
        fun listen(on: Boolean) {
            if (on == listening) return
            listening = on
            if (on) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            else sensors.unregisterListener(listener)
        }
        val observer = LifecycleEventObserver { _, _ -> listen(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            listen(false)
        }
    }
    return tilt
}
