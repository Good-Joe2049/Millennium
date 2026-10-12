package com.millennium.app.features.steamdb

import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization as L

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.millennium.app.features.steamdb.data.SteamDbRepository
import com.millennium.app.features.steamdb.data.SteamStorePageReader
import com.millennium.app.features.steamdb.lastupdate.SteamDbLastUpdateFeature
import com.millennium.app.features.steamdb.lowestprice.SteamDbLowestPriceFeature
import com.millennium.app.features.steamdb.rating.SteamDbRatingFeature
import com.millennium.app.features.steamdb.ui.SteamDbPanelView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Coordinates page identity, independent features and a shared cached repository. */
internal class SteamDbPanelController(private val emit: (Int, String) -> Unit) {
    private val repository = SteamDbRepository(emit)
    private val main = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<Activity, WeakReference<WebView>>()
    private val appIds = WeakHashMap<Activity, Int?>()
    private val panels = WeakHashMap<Activity, WeakReference<Panel>>()

    fun buildPanel(activity: Activity): Panel {
        panels[activity]?.get()?.let { close(it) }
        return Panel(activity).also { panel ->
            panels[activity] = WeakReference(panel)
            panel.view.onRefresh = { if (panel.active) refresh(activity, panel, force = true) }
        }
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
                it.unavailable(L.text(activity, R.string.db_open_panel))
            }
        }
    }

    fun refresh(activity: Activity, panel: Panel, force: Boolean = false) {
        panel.active = true
        val generation = ++panel.generation
        val webView = views[activity]?.get()
        val appId = SteamStorePageReader.appId(webView?.url)
        if (webView == null || !webView.isShown || appId == null) {
            panel.unavailable(L.text(activity, R.string.db_not_store))
            return
        }
        if (force) repository.invalidate(appId)
        panel.view.setPage(SteamStorePageReader.displayName(webView.url), appId)
        panel.loading()
        emit(Log.INFO, "steamdb panel request appId=$appId generation=$generation refresh=$force")

        // One ExtensionApp response feeds both online stats and last update.
        repository.appInfo(appId) { result ->
            if (!isCurrent(activity, panel, webView, appId, generation)) return@appInfo
            panel.online.showAppInfo(result)
            panel.update.show(result)
            result.onSuccess { panel.view.fetchedAt(it.fetchedAtMillis) }
            panel.finish("app", result.isSuccess)
            logResult("app-info", appId, result)
        }
        repository.currentPlayers(appId) { result ->
            if (!isCurrent(activity, panel, webView, appId, generation)) return@currentPlayers
            panel.online.showCurrentPlayers(result)
            panel.finish("players", result.isSuccess)
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
                panel.view.setPage(page.name ?: SteamStorePageReader.displayName(webView.url), appId)
                emit(Log.DEBUG, "steamdb page metadata appId=$appId attempt=$attempt currency=${page.currency} " +
                        "free=${page.free} positive=${page.positiveReviews} negative=${page.negativeReviews}")
                if (reviewsReady) panel.rating.show(page)
                if (!panel.priceRequested) {
                    val currency = page.currency
                    when {
                        page.free -> {
                            panel.priceRequested = true
                            panel.price.section.free()
                        }
                        currency != null -> {
                            panel.priceRequested = true
                            panel.pending.add("price")
                            repository.lowestPrice(appId, currency) { price ->
                                if (!isCurrent(activity, panel, webView, appId, generation)) return@lowestPrice
                                panel.price.show(price)
                                panel.finish("price", price.isSuccess)
                                logResult("lowest-price", appId, price)
                            }
                        }
                    }
                }
            }
            if ((!reviewsReady || !panel.priceRequested || page.name == null) && attempt < 3) {
                main.postDelayed({ readMetadata(activity, panel, webView, appId, generation, attempt + 1) }, 750L)
            } else {
                if (!reviewsReady) panel.rating.section.unavailable(L.text(activity, R.string.db_reviews_unavailable))
                if (!panel.priceRequested) panel.price.section.unavailable(L.text(activity, R.string.db_region_unavailable))
                panel.finish("metadata", reviewsReady && panel.priceRequested)
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
        private val sections = listOf(online.section, price.section, rating.section, update.section)
        val view = SteamDbPanelView(context, sections)
        val pending = mutableSetOf<String>()
        var active = false
        var generation = 0
        var priceRequested = false
        private var failed = false
        private var succeeded = false

        fun loading() {
            pending.clear()
            pending.addAll(listOf("app", "players", "metadata"))
            priceRequested = false
            failed = false
            succeeded = false
            online.loading()
            listOf(price.section, rating.section, update.section).forEach { it.loading() }
            view.loading()
        }

        fun finish(task: String, success: Boolean) {
            pending.remove(task)
            failed = failed || !success
            succeeded = succeeded || success
            if (pending.isEmpty()) view.finished(failed, succeeded)
        }

        fun unavailable(message: String) {
            pending.clear()
            sections.forEach { it.unavailable(message) }
            view.unavailable(message)
        }
    }
}
