package com.millennium.app.features.steamdb.lastupdate

import android.content.Context
import com.millennium.app.features.steamdb.data.SteamDbAppInfo
import com.millennium.app.features.steamdb.ui.SteamDbSection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal class SteamDbLastUpdateFeature(context: Context) {
    val section = SteamDbSection(context, "最近更新", listOf("更新日期"))

    fun show(result: Result<SteamDbAppInfo>) {
        result.onSuccess { info ->
            val timestamp = info.updatedAt
            if (timestamp == null) {
                section.unavailable("SteamDB 暂无更新日期")
                return@onSuccess
            }
            section.value(0, SteamDbSection.date(timestamp))
            val date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            val days = ChronoUnit.DAYS.between(date, LocalDate.now())
            val relative = when {
                days == 0L -> "今天"
                days > 0 -> "$days 天前"
                else -> "日期晚于设备当前日期"
            }
            section.status("$relative · 数据来源：SteamDB")
        }.onFailure {
            section.unavailable("更新日期暂时不可用")
        }
    }
}
