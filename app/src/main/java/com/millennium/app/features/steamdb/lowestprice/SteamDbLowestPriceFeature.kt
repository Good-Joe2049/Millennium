package com.millennium.app.features.steamdb.lowestprice

import android.content.Context
import com.millennium.app.features.steamdb.data.SteamDbLowestPrice
import com.millennium.app.features.steamdb.ui.SteamDbSection

internal class SteamDbLowestPriceFeature(context: Context) {
    val section = SteamDbSection(context, "SteamDB 历史最低价", listOf("历史最低价", "折扣", "出现次数", "最近出现"))

    fun show(result: Result<SteamDbLowestPrice>, currency: String) {
        result.onSuccess {
            section.value(0, it.price)
            section.value(1, it.limitedLabel ?: it.discount?.let { value -> "-$value%" } ?: "—")
            section.value(2, SteamDbSection.number(it.occurrences))
            section.value(3, SteamDbSection.date(it.lastAt))
            section.status("数据来源：SteamDB · $currency")
        }.onFailure {
            section.unavailable("历史最低价暂时不可用")
        }
    }
}
