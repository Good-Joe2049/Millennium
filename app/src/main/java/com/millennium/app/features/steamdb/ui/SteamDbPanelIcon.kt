package com.millennium.app.features.steamdb.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageButton
import androidx.core.graphics.withTranslation

/** Small vector controls, independent of the host app's resource IDs and theme. */
internal class SteamDbPanelIcon(private val kind: Kind) : Drawable() {
    enum class Kind { CLOSE, REFRESH, EXTERNAL }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SteamDbUi.TEXT
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    override fun draw(canvas: Canvas) {
        canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
            scale(bounds.width() / 24f, bounds.height() / 24f)
            when (kind) {
                Kind.CLOSE -> {
                    drawLine(6f, 6f, 18f, 18f, paint)
                    drawLine(18f, 6f, 6f, 18f, paint)
                }
                Kind.REFRESH -> {
                    drawArc(4f, 4f, 20f, 20f, -150f, 135f, false, paint)
                    drawArc(4f, 4f, 20f, 20f, 30f, 135f, false, paint)
                    arrow.reset()
                    arrow.moveTo(20f, 3f); arrow.lineTo(20f, 9f); arrow.lineTo(14f, 9f)
                    arrow.moveTo(4f, 21f); arrow.lineTo(4f, 15f); arrow.lineTo(10f, 15f)
                    drawPath(arrow, paint)
                }
                Kind.EXTERNAL -> {
                    arrow.reset()
                    arrow.moveTo(7f, 7f); arrow.lineTo(17f, 7f); arrow.lineTo(17f, 17f)
                    arrow.moveTo(7f, 17f); arrow.lineTo(17f, 7f)
                    drawPath(arrow, paint)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun button(context: Context, kind: Kind, description: String) = ImageButton(context).apply {
            contentDescription = description
            imageTintList = null
            backgroundTintList = null
            setImageDrawable(SteamDbPanelIcon(kind))
            val padding = SteamDbUi.dp(context, 16)
            setPadding(padding, padding, padding, padding)
            minimumWidth = SteamDbUi.dp(context, 48)
            minimumHeight = SteamDbUi.dp(context, 48)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val mask = SteamDbUi.background(context, 24, Color.WHITE)
            val plate = if (kind == Kind.CLOSE) {
                InsetDrawable(
                    SteamDbUi.background(context, 18, SteamDbUi.CARD, SteamDbUi.BORDER),
                    SteamDbUi.dp(context, 9),
                )
            } else null
            background = RippleDrawable(ColorStateList.valueOf(SteamDbUi.RIPPLE), plate, mask)
        }
    }
}
