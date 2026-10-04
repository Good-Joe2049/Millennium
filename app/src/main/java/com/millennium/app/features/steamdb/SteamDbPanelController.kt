package com.millennium.app.features.steamdb

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.LinearLayout
import com.millennium.app.features.steamdb.data.SteamDbRepository
import com.millennium.app.features.steamdb.data.SteamStorePageReader
import com.millennium.app.features.steamdb.lastupdate.SteamDbLastUpdateFeature
import com.millennium.app.features.steamdb.lowestprice.SteamDbLowestPriceFeature
import com.millennium.app.features.steamdb.rating.SteamDbRatingFeature
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Connects the current WebView and four independent sections to the shared repository. */
internal class SteamDbPanelController(private val emit: (Int, String) -> Unit) {
    private val repository = SteamDbRepository(emit)
    private val main = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<Activity, WeakReference<WebView>>()
    private val appIds = WeakHashMap<Activity, Int?>()
    private val panels = WeakHashMap<Activity, WeakReference<Panel>>()

    fun buildPanel(activity: Activity): Panel {
        panels[activity]?.get()?.let { close(it) }
        return Panel(activity).also { panels[activity] = WeakReference(it) }
    }

    fun observe(webView: WebView) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            webView.post { observe(webView) }
            return
        }
        if (!webView.isShown) return
        val activity = findActivity(webView.context) ?: return
        val appId = SteamStorePageReader.appId(webView.url)
        if (views[activity]?.get() === webView && appIds[activity] == appId) return
        views[activity] = WeakReference(webView)
        appIds[activity] = appId
        emit(Log.INFO, "steamdb page detected appId=$appId")
        panels[activity]?.get()?.let {
            if (it.active) refresh(activity, it)
            else {
                it.generation++
                it.unavailable("打开面板后读取当前游戏数据")
            }
        }
    }

    fun refresh(activity: Activity, panel: Panel) {
        panel.active = true
        val generation = ++panel.generation
        val webView = views[activity]?.get()
        val appId = SteamStorePageReader.appId(webView?.url)
        if (webView == null || !webView.isShown || appId == null) {
            panel.unavailable("当前不是 Steam 游戏商店页面")
            return
        }
        panel.online.loading()
        panel.price.section.loading()
        panel.rating.section.loading()
        panel.update.section.loading()
        panel.priceRequested = false
        emit(Log.INFO, "steamdb panel request appId=$appId generation=$generation")

        // One ExtensionApp response feeds both online stats and last update.
        repository.appInfo(activity, appId) { result ->
            if (!isCurrent(activity, panel, webView, appId, generation)) return@appInfo
            panel.online.showAppInfo(result)
            panel.update.show(result)
            logResult("app-info", appId, result)
        }
        // Independent: Steam can succeed even when SteamDB is unavailable, and vice versa.
        repository.currentPlayers(activity, appId) { result ->
            if (!isCurrent(activity, panel, webView, appId, generation)) return@currentPlayers
            panel.online.showCurrentPlayers(result)
            logResult("current-players", appId, result)
        }
        readMetadata(activity, panel, webView, appId, generation, 0)
    }

    private fun readMetadata(activity: Activity, panel: Panel, webView: WebView, appId: Int, generation: Int, attempt: Int) {
        if (!isCurrent(activity, panel, webView, appId, generation)) return
        SteamStorePageReader.read(webView, appId) { result ->
            if (!isCurrent(activity, panel, webView, appId, generation)) return@read
            val page = result.getOrNull()
            val reviewsReady = page?.positiveReviews != null && page.negativeReviews != null
            if (page != null) {
                emit(Log.DEBUG, "steamdb page metadata appId=$appId attempt=$attempt currency=${page.currency} " +
                        "free=${page.free} positive=${page.positiveReviews} negative=${page.negativeReviews}")
                if (reviewsReady) panel.rating.show(page)
                if (!panel.priceRequested) {
                    val currency = page.currency
                    when {
                        page.free -> {
                            panel.priceRequested = true
                            panel.price.section.unavailable("免费游戏不查询历史最低价")
                        }
                        currency != null -> {
                            panel.priceRequested = true
                            repository.lowestPrice(activity, appId, currency) { price ->
                                if (!isCurrent(activity, panel, webView, appId, generation)) return@lowestPrice
                                panel.price.show(price, currency)
                                logResult("lowest-price", appId, price)
                            }
                        }
                    }
                }
            }
            if ((!reviewsReady || !panel.priceRequested) && attempt < 3) {
                // React may mount the review/currency metadata after the WebView URL changes.
                main.postDelayed({ readMetadata(activity, panel, webView, appId, generation, attempt + 1) }, 750L)
            } else {
                if (!reviewsReady) panel.rating.section.unavailable("未读取到商店评价数据")
                if (!panel.priceRequested) panel.price.section.unavailable("未识别到商店币种或定价地区")
                if (!reviewsReady || !panel.priceRequested) {
                    emit(Log.WARN, "steamdb page metadata incomplete appId=$appId reviewsReady=$reviewsReady " +
                            "priceReady=${panel.priceRequested} error=${result.exceptionOrNull()}")
                }
            }
        }
    }

    fun close(panel: Panel) {
        panel.active = false
        panel.generation++
    }

    fun detach(activity: Activity) {
        panels.remove(activity)?.get()?.let { close(it) }
        views.remove(activity)
        appIds.remove(activity)
    }

    private fun isCurrent(activity: Activity, panel: Panel, webView: WebView, appId: Int, generation: Int): Boolean =
        panel.active && panel.generation == generation && !activity.isFinishing && !activity.isDestroyed &&
                views[activity]?.get() === webView && webView.isShown && SteamStorePageReader.appId(webView.url) == appId

    private fun logResult(feature: String, appId: Int, result: Result<*>) {
        result.onSuccess { emit(Log.INFO, "steamdb feature loaded feature=$feature appId=$appId") }
            .onFailure { emit(Log.WARN, "steamdb feature failed feature=$feature appId=$appId error=$it") }
    }

    private fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return null
    }

    class Panel(context: Context) {
        val online = SteamDbOnlineStatsFeature(context)
        val price = SteamDbLowestPriceFeature(context)
        val rating = SteamDbRatingFeature(context)
        val update = SteamDbLastUpdateFeature(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            listOf(online.section, price.section, rating.section, update.section).forEach {
                addView(it.root, LinearLayout.LayoutParams(-1, -2))
            }
        }
        var active = false
        var generation = 0
        var priceRequested = false

        fun unavailable(message: String) {
            listOf(online.section, price.section, rating.section, update.section).forEach { it.unavailable(message) }
        }
    }
}
