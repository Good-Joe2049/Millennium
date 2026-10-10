package com.millennium.app.features.ui.floating

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.millennium.app.features.steamdb.ui.SteamDbUi
import kotlin.math.roundToInt

/** Container foreground: both strokes remain above the shared icon and inside its clipping outline. */
internal class SteamFloatingBorder(
    private val lineWidth: Float,
    private var cornerRadius: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
    }
    private val rect = RectF()
    private var panelProgress = 0f
    private var drawableAlpha = 255

    fun update(radius: Float, progress: Float) {
        cornerRadius = radius
        panelProgress = progress.coerceIn(0f, 1f)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        drawStroke(canvas, lineWidth / 2f, ColorUtils.blendARGB(0x59ffffff, SteamDbUi.BORDER, panelProgress))
        if (panelProgress < 1f) {
            drawStroke(canvas, lineWidth * 1.5f, Color.argb((255 * (1f - panelProgress)).roundToInt(), 0, 0, 0))
        }
    }

    private fun drawStroke(canvas: Canvas, inset: Float, color: Int) {
        paint.color = color
        paint.alpha = (Color.alpha(color) * drawableAlpha / 255f).roundToInt()
        rect.set(bounds)
        rect.inset(inset, inset)
        val radius = (cornerRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
