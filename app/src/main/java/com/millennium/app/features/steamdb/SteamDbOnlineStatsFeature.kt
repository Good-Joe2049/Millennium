package com.millennium.app.features.steamdb

import android.content.Context
import com.millennium.app.features.steamdb.data.SteamDbAppInfo
import com.millennium.app.features.steamdb.ui.SteamDbSection

/** Online stats presentation; networking and caching live in the shared data layer. */
internal class SteamDbOnlineStatsFeature(context: Context) {
    val section = SteamDbSection(context, "SteamDB 在线数据",
        listOf("实时在线人数", "24 小时峰值人数", "历史峰值人数", "关注数"))
    private var appInfo: SteamDbAppInfo? = null
    private var currentPlayers: Int? = null
    private var appFinished = false
    private var playersFinished = false

    fun loading() {
        appInfo = null
        currentPlayers = null
        appFinished = false
        playersFinished = false
        section.loading()
    }

    fun showAppInfo(result: Result<SteamDbAppInfo>) {
        appInfo = result.getOrNull()
        appFinished = true
        render()
    }

    fun showCurrentPlayers(result: Result<Int>) {
        currentPlayers = result.getOrNull()
        playersFinished = true
        render()
    }

    private fun render() {
        val current = currentPlayers ?: appInfo?.currentPlayers
        section.value(0, if (current == null && (!playersFinished || !appFinished)) "…" else SteamDbSection.number(current))
        val historic = listOf(appInfo?.peakToday, appInfo?.peakAll, appInfo?.followers)
        historic.forEachIndexed { index, value ->
            section.value(index + 1, if (!appFinished) "…" else SteamDbSection.number(value))
        }
        section.status(when {
            !appFinished -> "正在读取 SteamDB 数据"
            appInfo == null -> "SteamDB 数据暂时不可用"
            !playersFinished -> "正在更新实时在线人数"
            currentPlayers == null -> "数据来源：SteamDB（实时人数使用 SteamDB 记录）"
            else -> "数据来源：SteamDB / Steam"
        })
    }
}
