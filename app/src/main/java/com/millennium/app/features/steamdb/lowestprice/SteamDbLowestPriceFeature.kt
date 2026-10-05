package com.millennium.app.features.steamdb.lowestprice

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.millennium.app.features.steamdb.data.SteamDbLowestPrice
import com.millennium.app.features.steamdb.ui.SteamDbSectionView
import com.millennium.app.features.steamdb.ui.SteamDbUi as Ui

internal class SteamDbLowestPriceFeature(context: Context) {
    val section = LowestPriceSection(context)
    fun show(result: Result<SteamDbLowestPrice>) {
        result.onSuccess { section.show(it) }.onFailure { section.unavailable("历史最低价暂时不可用") }
    }
}

@SuppressLint("SetTextI18n")
internal class LowestPriceSection(context: Context) : SteamDbSectionView {
    override val root = Ui.column(context).apply {
        setPadding(0, Ui.dp(context, 13), 0, Ui.dp(context, 12))
    }
    private val priceValue = Ui.value(context)
    private val limitedLabel = Ui.label(context, "2年内史低价").apply { gravity = Gravity.END }
    private val limitedValue = Ui.value(context).apply { gravity = Gravity.END }
    private val discount = Ui.label(context, "").apply { gravity = Gravity.END }
    private val note = Ui.text(context, "", 10f).apply { minLines = 2 }

    init {
        val labels = Ui.row(context).apply {
            addView(Ui.label(context, "历史最低价"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(limitedLabel, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(labels, LinearLayout.LayoutParams(-1, -2))
        val values = Ui.row(context).apply {
            addView(priceValue, LinearLayout.LayoutParams(0, -2, 1f))
            addView(limitedValue, LinearLayout.LayoutParams(0, -2, 1f))
            addView(discount, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(values, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(context, 4)
        })
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 4) })
        unavailable("打开游戏商店页后查看")
    }

    fun show(price: SteamDbLowestPrice) {
        priceValue.text = price.price
        val limited = price.limitedPrice?.takeIf { it.isNotBlank() }
        limitedLabel.visibility = if (limited != null) View.VISIBLE else View.GONE
        limitedValue.visibility = limitedLabel.visibility
        limitedValue.text = limited.orEmpty()
        discount.text = if (limited == null) price.discount?.let { "-$it%" }.orEmpty() else ""
        discount.visibility = if (discount.text.isNotEmpty()) View.VISIBLE else View.GONE
        val date = price.lastAt?.let { "最近出现 ${Ui.dateWithRelative(it)}" } ?: "暂无出现日期"
        val count = price.occurrences?.takeIf { it > 1 }?.let { " · 出现 $it 次" }.orEmpty()
        note.text = date + count
    }

    fun free() {
        reset()
        priceValue.text = "免费游戏"
        note.text = "无需购买"
    }

    override fun loading() {
        reset()
        priceValue.text = "…"
        note.text = "正在读取在线数据"
    }

    override fun unavailable(message: String) {
        reset()
        priceValue.text = "—"
        note.text = message
    }

    private fun reset() {
        limitedLabel.visibility = View.GONE
        limitedValue.visibility = View.GONE
        limitedValue.text = ""
        discount.visibility = View.GONE
        discount.text = ""
    }
}
