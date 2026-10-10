package com.millennium.app.features.steamguard.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.millennium.app.features.steamguard.export.SteamGuardQrFormat
import com.millennium.app.features.steamguard.export.SteamGuardQrPair
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun SteamGuardQrMorph(
    codes: SteamGuardQrPair,
    format: SteamGuardQrFormat,
    modifier: Modifier = Modifier,
) {
    val target = if (format == SteamGuardQrFormat.Stratum) 0f else 1f
    val progress = remember(codes) { Animatable(target) }
    val renderer = remember(codes) { SteamGuardQrMorphRenderer(codes) }
    val accentColor = MiuixTheme.colorScheme.primary.toArgb()
    LaunchedEffect(codes, target) {
        // Keep the demo's 1.6s timing; reverse from the current frame on rapid taps.
        // Animatable also honors Android's system animation duration scale.
        if (progress.value != target) {
            progress.animateTo(
                target,
                tween((1600 * maxOf(0.15f, abs(target - progress.value))).roundToInt(), easing = LinearEasing),
            )
        }
    }
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "${format.label} 导入二维码"
            stateDescription = if (progress.isRunning) "变形中" else "可扫描"
        },
    ) {
        drawIntoCanvas {
            renderer.draw(it.nativeCanvas, size.width.roundToInt(), size.height.roundToInt(), progress.value, accentColor)
        }
    }
}

/** Port of the supplied QrMorphView's random stagger, cubic easing and rounded-cell effect. */
internal class SteamGuardQrMorphRenderer(private val codes: SteamGuardQrPair) {
    private val count = codes.stratum.width
    private val delays = FloatArray(count * count) { Random.nextFloat() }.apply {
        val maximum = maxOrNull()?.coerceAtLeast(0.0001f) ?: 1f
        indices.forEach { this[it] /= maximum }
    }
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private val rect = RectF()

    fun draw(canvas: AndroidCanvas, width: Int, height: Int, progress: Float, accentColor: Int) {
        paint.isAntiAlias = false
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // Four modules of quiet zone on every side; no bitmap scaling or fractional grid.
        val cell = minOf(width, height) / (count + 8)
        if (cell == 0) return
        val left = (width - count * cell) / 2
        val top = (height - count * cell) / 2
        val p = progress.coerceIn(0f, 1f)
        for (row in 0 until count) for (column in 0 until count) {
            val start = codes.stratum[column, row]
            val end = codes.aegis[column, row]
            if (!start && !end) continue
            val x = (left + column * cell).toFloat()
            val y = (top + row * cell).toFloat()
            val scale = if (start == end) 1f else {
                val local = ((p - delays[row * count + column] * 0.55f) / 0.45f).coerceIn(0f, 1f)
                val t = if (local < 0.5f) 4f * local * local * local else {
                    val inverse = 1f - local
                    1f - 4f * inverse * inverse * inverse
                }
                if (start) 1f - t else t
            }
            if (scale <= 0f) continue
            if (scale >= 1f) {
                // Exact square endpoints and unchanged black modules remain pixel sharp.
                paint.isAntiAlias = false
                paint.color = Color.BLACK
                canvas.drawRect(x, y, x + cell, y + cell, paint)
            } else {
                val side = cell * scale
                val offset = (cell - side) / 2f
                val radius = minOf(side / 2f, (1f - scale) * side * 0.5f + 0.5f)
                val mix = sin(scale * Math.PI).toFloat()
                paint.isAntiAlias = true
                // The miuix primary color appears only while a cell is changing shape.
                paint.color = Color.rgb(
                    (Color.red(accentColor) * mix).roundToInt(),
                    (Color.green(accentColor) * mix).roundToInt(),
                    (Color.blue(accentColor) * mix).roundToInt(),
                )
                rect.set(x + offset, y + offset, x + offset + side, y + offset + side)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }
}
