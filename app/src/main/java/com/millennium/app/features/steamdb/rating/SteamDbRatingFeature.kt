package com.millennium.app.features.steamdb.rating

import android.content.Context
import com.millennium.app.features.steamdb.data.SteamStorePage
import com.millennium.app.features.steamdb.ui.SteamDbSection
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

internal class SteamDbRatingFeature(context: Context) {
    val section = SteamDbSection(context, "SteamDB 评分", listOf("评分", "评价总数"))

    fun show(page: SteamStorePage) {
        val positive = page.positiveReviews
        val negative = page.negativeReviews
        if (positive == null || negative == null) {
            section.unavailable("未读取到商店评价数据")
            return
        }
        val total = positive + negative
        val score = calculate(positive, negative)
        section.value(0, score?.let { String.format(Locale.US, "%.2f%%", it * 100) } ?: "—")
        section.value(1, SteamDbSection.number(total))
        section.status(if (total == 0L) "暂无评价" else "Steam 商店评价 · SteamDB 评分算法")
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
