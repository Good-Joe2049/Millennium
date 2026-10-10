package com.millennium.app.features.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewParent
import android.widget.TextView
import android.webkit.WebView
import com.millennium.app.features.ui.floating.SteamFloatingPanelFeature
import com.millennium.app.features.ui.menu.SteamMainMenuSettingFeature
import com.millennium.app.features.ui.settings.SteamModuleSettingFeature
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic-only probe for locating semantic anchors in Steam's React Native UI. */
internal class SteamUiProbeFeature(
    private val emit: (priority: Int, message: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installedApplications = WeakHashMap<Application, Boolean>()
    private val activityStates = IdentityHashMap<Activity, ActivityState>()
    private val reactNativeTagIdsByLoader = WeakHashMap<ClassLoader, Map<String, Int>>()
    private val floatingPanelFeature = SteamFloatingPanelFeature(emit)
    private val moduleSettingFeature = SteamModuleSettingFeature(emit) { activity, enabled ->
        if (enabled) floatingPanelFeature.attach(activity)
        else floatingPanelFeature.hideAll()
    }
    private val menuSettingFeature = SteamMainMenuSettingFeature(
        emit = emit,
        iconProvider = { activity -> floatingPanelFeature.moduleIcon(activity) },
        onOpen = { activity -> moduleSettingFeature.show(activity) },
    )
    // The Activity hook must keep a Boolean contract, but the floating entry handles its own touches.
    private val touchInterceptionEnabled = AtomicBoolean(false)

    fun setModuleApkPath(path: String?) {
        floatingPanelFeature.setModuleApkPath(path)
        moduleSettingFeature.setModuleApkPath(path)
    }

    @Synchronized
    fun install(context: Context?) {
        val application = context?.applicationContext as? Application ?: return
        if (installedApplications.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(callbacks)
        emit(Log.INFO, "probe installed package=${application.packageName}")
    }

    fun onActivityTouch(activity: Activity, event: android.view.MotionEvent): Boolean {
        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) return false
        val decor = activity.window?.decorView ?: return false
        val rawX = event.rawX
        val rawY = event.rawY
        val target = findDeepestViewAt(decor, rawX, rawY)
        if (target == null) {
            emit(Log.INFO, "touch down activity=${activity.javaClass.name} raw=$rawX,$rawY target=none")
            return false
        }
        emit(
            Log.INFO,
            "touch down activity=${activity.javaClass.name} raw=$rawX,$rawY " +
                    "target=${describeTouchView(target)} path=${describeTouchPath(target)}",
        )
        if (target is WebView) inspectWebViewTouch(target, rawX, rawY)
        return touchInterceptionEnabled.get()
    }

    /** Kept for the diagnostic resolver hook; the floating entry never intercepts Steam UI touches. */
    @Suppress("UNUSED_PARAMETER")
    fun onReactTouchTarget(viewTag: Int, target: View?): Boolean = false

    private fun findDeepestViewAt(view: View, rawX: Float, rawY: Float): View? {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return null
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || !bounds.contains(rawX.toInt(), rawY.toInt())) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findDeepestViewAt(view.getChildAt(index), rawX, rawY)?.let { return it }
            }
        }
        return view
    }

    private fun describeTouchView(view: View): String {
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        return "${view.javaClass.name} bounds=$bounds ${semanticAttributes(view)}"
    }

    private fun describeTouchPath(view: View): String {
        val path = ArrayList<String>(8)
        path += view.javaClass.name
        var current: ViewParent? = view.parent
        var depth = 0
        while (current is View && depth < 8) {
            path += current.javaClass.name
            current = current.parent
            depth++
        }
        return path.joinToString("<-")
    }

    @Suppress("DEPRECATION")
    private fun inspectWebViewTouch(webView: WebView, rawX: Float, rawY: Float) {
        val bounds = Rect()
        if (!webView.getGlobalVisibleRect(bounds)) return
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val pageX = ((rawX - bounds.left) / scale + webView.scrollX).coerceAtLeast(0f)
        val pageY = ((rawY - bounds.top) / scale + webView.scrollY).coerceAtLeast(0f)
        val script = """
(function() {
  var x = $pageX;
  var y = $pageY;
  var elements = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
  return JSON.stringify(elements.slice(0, 8).map(function(element) {
    var rect = element.getBoundingClientRect();
    return {
      tag: element.tagName,
      id: element.id || '',
      className: typeof element.className === 'string' ? element.className.slice(0, 120) : '',
      href: element.href || element.getAttribute('href') || '',
      text: (element.innerText || element.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 100),
      rect: [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height)]
    };
  }));
})();
        """.trimIndent()
        runCatching {
            webView.evaluateJavascript(script) { result ->
                emit(
                    Log.INFO,
                    "web touch point raw=$rawX,$rawY page=$pageX,$pageY result=$result",
                )
            }
        }.onFailure {
            emit(Log.ERROR, "web touch inspection failed: ${it.javaClass.name}: ${it.message}")
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
            if (activity.packageName == STEAM_PACKAGE) {
                moduleSettingFeature.restoreState(activity, savedInstanceState)
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            if (activity.packageName != STEAM_PACKAGE) return
            touchInterceptionEnabled.set(false)
            floatingPanelFeature.attach(activity)
            menuSettingFeature.attach(activity)
            moduleSettingFeature.resume(activity)
            attachLayoutProbe(activity)
            startPolling(activity)
            scheduleDump(activity, "resume")
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity.packageName == STEAM_PACKAGE) {
                // Keep the settings window and its selected tab while Steam is in the background.
                menuSettingFeature.detach(activity)
                floatingPanelFeature.detach(activity)
            }
            stopPolling(activity)
        }

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: android.os.Bundle,
        ) {
            if (activity.packageName == STEAM_PACKAGE) {
                moduleSettingFeature.saveState(activity, outState)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity.packageName == STEAM_PACKAGE) {
                moduleSettingFeature.detach(activity)
                menuSettingFeature.detach(activity)
                floatingPanelFeature.detach(activity)
            }
            val state = activityStates.remove(activity) ?: return
            stopPolling(state)
            state.decorView.viewTreeObserver.removeOnGlobalLayoutListener(state.listener)
            state.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(state.focusListener)
        }
    }

    private fun attachLayoutProbe(activity: Activity) {
        val decorView = activity.window?.decorView ?: return
        val current = activityStates[activity]
        if (current?.decorView === decorView) return
        if (current != null) {
            current.decorView.viewTreeObserver.removeOnGlobalLayoutListener(current.listener)
            current.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(current.focusListener)
        }

        val state = ActivityState(decorView)
        state.listener = ViewTreeObserver.OnGlobalLayoutListener {
            scheduleDump(activity, "layout")
        }
        state.focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            emit(
                Log.DEBUG,
                "focus old=${describeFocus(oldFocus)} new=${describeFocus(newFocus)}",
            )
            scheduleDump(activity, "focus")
        }
        decorView.viewTreeObserver.addOnGlobalLayoutListener(state.listener)
        decorView.viewTreeObserver.addOnGlobalFocusChangeListener(state.focusListener)
        activityStates[activity] = state
    }

    private fun startPolling(activity: Activity) {
        val state = activityStates[activity] ?: return
        stopPolling(state)
        val token = ++state.pollToken
        val poll = object : Runnable {
            override fun run() {
                if (state.pollToken != token || activity.isFinishing || activity.isDestroyed) return
                dumpVisibleTree(activity, state, "poll")
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        state.pollRunnable = poll
        mainHandler.post(poll)
    }

    private fun stopPolling(activity: Activity) {
        activityStates[activity]?.let(::stopPolling)
    }

    private fun stopPolling(state: ActivityState) {
        state.pollToken++
        state.pollRunnable?.let(mainHandler::removeCallbacks)
        state.pollRunnable = null
    }

    private fun scheduleDump(activity: Activity, reason: String) {
        val state = activityStates[activity] ?: return
        if (state.dumpPending) return
        state.dumpPending = true
        mainHandler.postDelayed({
            state.dumpPending = false
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            dumpVisibleTree(activity, state, reason)
        }, DUMP_DELAY_MS)
    }

    private fun dumpVisibleTree(activity: Activity, state: ActivityState, reason: String) {
        val nodes = ArrayList<NodeDescription>(MAX_NODES)
        collectVisibleNodes(state.decorView, 0, nodes)
        val signature = nodes.joinToString("|") { it.signature }
        if (signature == state.lastSignature) return
        state.lastSignature = signature

        emit(
            Log.INFO,
            "activity=${activity.javaClass.name} reason=$reason root=" +
                    "${state.decorView.javaClass.name} nodes=${nodes.size}",
        )
        nodes.forEachIndexed { index, node ->
            emit(
                Log.DEBUG,
                "node[$index] depth=${node.depth} class=${node.className} " +
                        "bounds=${node.bounds} ${node.attributes}",
            )
        }
    }

    private fun collectVisibleNodes(view: View, depth: Int, output: MutableList<NodeDescription>) {
        if (output.size >= MAX_NODES || depth > MAX_DEPTH) return
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return

        if (view is WebView) floatingPanelFeature.observe(view)

        val attributes = semanticAttributes(view)
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        output += NodeDescription(
            depth = depth,
            className = view.javaClass.name,
            bounds = "${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}",
            attributes = attributes.ifEmpty { "attributes=none" },
        )

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectVisibleNodes(view.getChildAt(index), depth + 1, output)
                if (output.size >= MAX_NODES) return
            }
        }
    }

    private fun semanticAttributes(view: View): String {
        val values = ArrayList<String>(12)
        if (view.id != View.NO_ID) {
            val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            values += if (idName != null) "id=$idName" else "viewTag=${view.id}"
        }
        reactNativeTag("testID", view)?.let { values += "testID=${shorten(it)}" }
        reactNativeTag("nativeID", view)?.let { values += "nativeID=${shorten(it)}" }
        view.tag?.toString()?.takeIf(String::isNotBlank)?.let {
            values += "tag=${shorten(it)}"
        }
        view.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let {
            values += "description=${shorten(it)}"
        }
        if (view is TextView) {
            view.text?.toString()?.takeIf(String::isNotBlank)?.let {
                values += "text=${shorten(it)}"
            }
            view.hint?.toString()?.takeIf(String::isNotBlank)?.let {
                values += "hint=${shorten(it)}"
            }
        }
        if (view.isClickable || view.isLongClickable || view.isFocusable) {
            values += "interactive=clickable:${view.isClickable},long:${view.isLongClickable},focusable:${view.isFocusable}"
        }
        if (!view.isEnabled) values += "enabled=false"
        if (view.isSelected) values += "selected=true"
        if (view.isActivated) values += "activated=true"
        if (view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            values += "importantForAccessibility=${view.importantForAccessibility}"
        }
        if (view is WebView) {
            view.url?.takeIf(String::isNotBlank)?.let { values += "webViewUrl=${shorten(it)}" }
        }
        if (view is ViewGroup) values += "children=${view.childCount}"
        return values.joinToString(" ")
    }

    private fun describeFocus(view: View?): String {
        if (view == null) return "none"
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        return "${view.javaClass.name}@${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}"
    }

    private fun reactNativeTag(kind: String, view: View): String? {
        val tagIds = synchronized(reactNativeTagIdsByLoader) {
            reactNativeTagIdsByLoader.getOrPut(view.context.classLoader) {
                loadReactNativeTagIds(view.context.classLoader)
            }
        }
        val tagId = tagIds[kind] ?: return null
        return runCatching { view.getTag(tagId) as? String }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun loadReactNativeTagIds(classLoader: ClassLoader): Map<String, Int> {
        val reactIds = runCatching {
            Class.forName("com.facebook.react.R" + '$' + "id", false, classLoader)
        }.getOrNull() ?: return emptyMap()
        return mapOf(
            "testID" to (reactIds.field("react_test_id") ?: 0),
            "nativeID" to (reactIds.field("react_native_id") ?: 0),
        ).filterValues { it != 0 }
    }

    private fun shorten(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_ATTRIBUTE_LENGTH)

    private data class NodeDescription(
        val depth: Int,
        val className: String,
        val bounds: String,
        val attributes: String,
    ) {
        val signature: String
            get() = "$depth|$className|$bounds|$attributes"
    }

    private class ActivityState(val decorView: View) {
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener
        var dumpPending = false
        var lastSignature = ""
        var pollToken = 0
        var pollRunnable: Runnable? = null
    }

    private companion object {
        const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"
        const val DUMP_DELAY_MS = 250L
        const val POLL_INTERVAL_MS = 750L
        const val MAX_DEPTH = 24
        const val MAX_NODES = 1000
        const val MAX_ATTRIBUTE_LENGTH = 120
        private fun Class<*>.field(name: String): Int? =
            runCatching { getDeclaredField(name).getInt(null) }.getOrNull()
    }
}
