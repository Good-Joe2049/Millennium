package com.millennium.app.features.steamdb.rating

import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization as L

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.millennium.app.features.steamdb.data.SteamStorePage
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal class SteamDbRatingFeature(private val context: Context) {
    val section = RatingSection(context)
    fun show(page: SteamStorePage) {
        val positive = page.positiveReviews
        val negative = page.negativeReviews
        if (positive == null || negative == null) {
            section.unavailable(L.text(context, R.string.db_reviews_unavailable))
            return
        }
        section.show(calculate(positive, negative), positive + negative)
    }

    companion object {
        fun calculate(positive: Long, negative: Long): Double? {
            if (positive < 0 || negative < 0) return null
            val total = positive.toDouble() + negative.toDouble()
            if (total == 0.0) return null
            val average = positive / total
            return average - (average - 0.5) * 2.0.pow(-log10(total + 1))
        }
    }
}

@SuppressLint("SetTextI18n")
internal class RatingSection(private val context: Context) : SteamDbSectionView {
    override val root = Ui.column(context).apply {
        setPadding(0, Ui.dp(context, 14), 0, Ui.dp(context, 14))
    }
    private val count = Ui.text(context, "—", 11f).apply { gravity = Gravity.END }
    private val score = Ui.value(context)
    private val hint = Ui.text(context, L.text(context, R.string.db_rating_adjusted), 10f).apply { gravity = Gravity.END }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 10_000
        progress = 0
        progressDrawable = LayerDrawable(arrayOf(
            Ui.background(context, 2, Ui.LINE),
            ClipDrawable(Ui.background(context, 2, Ui.ACCENT), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        setPadding(0, 0, 0, 0)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        root.addView(Ui.row(context).apply {
            addView(Ui.label(context, L.text(context, R.string.db_rating)), LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Ui.row(context).apply {
            addView(score, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hint, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 5) })
        root.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(context, 3)).apply {
            topMargin = Ui.dp(context, 7)
        })
    }

    fun show(value: Double?, total: Long) {
        score.text = value?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "—"
        count.text = L.quantity(context, R.plurals.db_reviews, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Ui.number(total))
        hint.text = if (total == 0L) L.text(context, R.string.db_reviews_empty) else L.text(context, R.string.db_rating_adjusted)
        progress.setProgress(((value ?: 0.0).coerceIn(0.0, 1.0) * 10_000).roundToInt(), true)
    }

    override fun loading() {
        score.text = "…"
        count.text = L.text(context, R.string.db_reviews_unknown)
        hint.text = L.text(context, R.string.db_rating_adjusted)
        progress.setProgress(0, false)
    }

    override fun unavailable(message: String) {
        score.text = "—"
        count.text = L.text(context, R.string.db_reviews_unknown)
        hint.text = message
        progress.setProgress(0, false)
    }
}
