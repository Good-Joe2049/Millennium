package com.millennium.app.features.ui.floating

import android.annotation.SuppressLint
import android.app.Activity
import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.millennium.app.features.steamdb.ui.SteamDbUi
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.content.res.ResourcesCompat
import com.millennium.app.R
import com.millennium.app.features.steamdb.SteamDbPanelController
import com.millennium.app.features.ui.settings.SteamModuleSettings
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.min

/**
 * In-window floating entry and same-container SteamDB panel.
 * The root is attached to the current Activity instead of a system window.
 */
internal class SteamFloatingPanelFeature(
    private val emit: (priority: Int, message: String) -> Unit,
) {
    private val steamDbPanelController = SteamDbPanelController(emit)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedWebViews = WeakHashMap<WebView, Boolean>()
    private val observedUrls = WeakHashMap<WebView, String>()
    private val injectedUrls = WeakHashMap<WebView, String>()
    private val floatingStates = IdentityHashMap<Activity, FloatingState>()
    private val moduleResourcesLock = Any()
    @Volatile
    private var moduleApkPath: String? = null
    @Volatile
    private var moduleResourcesBundle: ModuleResourcesBundle? = null

    fun setModuleApkPath(path: String?) {
        synchronized(moduleResourcesLock) {
            if (moduleApkPath == path) return
            moduleApkPath = path?.takeIf(String::isNotBlank)
            moduleResourcesBundle = null
        }
        emit(
            Log.INFO,
            "floating panel module APK resource path updated available=${!moduleApkPath.isNullOrBlank()} " +
                    "path=$moduleApkPath",
        )
    }

    fun moduleIcon(activity: Activity): Drawable? = loadIcon(activity)

    fun attach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { attach(activity) }
            return
        }
        if (activity.isFinishing || activity.isDestroyed || !SteamModuleSettings.isSteamDbEnabled(activity)) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val current = floatingStates[activity]
        if (current != null && current.root.parent === decor) return
        current?.root?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val root = FrameLayout(activity).apply {
            tag = ROOT_TAG
            clipChildren = false
            clipToPadding = false
            isClickable = false
        }
        val scrim = View(activity).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            isClickable = true
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val card = TouchFrameLayout(activity).apply {
            tag = CARD_TAG
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToOutline = false
            outlineProvider = roundedCardOutline(activity)
            background = cardBackground(activity)
            foreground = SteamFloatingBorder(
                activity.resources.displayMetrics.density,
                dp(activity, BUBBLE_SIZE_DP / 2).toFloat(),
            )
            elevation = dp(activity, 6).toFloat()
        }
        val bubble = buildBubble(activity)
        card.addView(bubble, FrameLayout.LayoutParams(-1, -1))
        root.addView(card, FrameLayout.LayoutParams(dp(activity, BUBBLE_SIZE_DP), dp(activity, BUBBLE_SIZE_DP)))
        decor.addView(root, ViewGroup.LayoutParams(-1, -1))

        lateinit var state: FloatingState
        val statsPanel = steamDbPanelController.buildPanel(activity)
        val panel = statsPanel.view.wrap { collapse(state) }
        panel.visibility = View.GONE
        card.addView(panel, FrameLayout.LayoutParams(-1, -1))
        val sharedIcon = buildIconView(activity, loadIcon(activity))
        card.addView(
            sharedIcon,
            FrameLayout.LayoutParams(dp(activity, PANEL_ICON_SIZE_DP), dp(activity, PANEL_ICON_SIZE_DP)),
        )
        sharedIcon.bringToFront()
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            card.invalidateOutline()
        }
        state = FloatingState(activity, root, scrim, card, bubble, panel, sharedIcon, statsPanel)
        floatingStates[activity] = state
        scrim.setOnClickListener { collapse(state) }
        installBubbleTouch(state)
        root.post {
            placeBubble(state)
            positionSharedIcon(state, opening = false, progress = 1f)
            refreshSharedIcon(state)
            if (state.sharedIcon.drawable == null) {
                mainHandler.postDelayed({
                    if (state.root.parent != null) refreshSharedIcon(state)
                }, ICON_REFRESH_DELAY_MS)
            }
            state.card.invalidateOutline()
            state.card.clipToOutline = true
            state.sharedIcon.bringToFront()
            logIconLayout(state, "attached")
            val params = state.card.layoutParams as? FrameLayout.LayoutParams
            emit(
                Log.INFO,
                "floating layout root=${root.width}x${root.height} " +
                        "card=${state.card.width}x${state.card.height} " +
                        "left=${params?.leftMargin} top=${params?.topMargin} " +
                        "bubble=${state.bubble.javaClass.name} clickable=${state.bubble.isClickable}",
            )
        }
        emit(Log.INFO, "floating bubble attached activity=${activity.javaClass.name}")
    }

    fun hideAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideAll() }
            return
        }
        floatingStates.keys.toList().forEach(::detach)
    }

    fun detach(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { detach(activity) }
            return
        }
        steamDbPanelController.detach(activity)
        val state = floatingStates.remove(activity) ?: return
        if (!state.expanded) saveBubblePosition(state)
        state.animator?.cancel()
        (state.root.parent as? ViewGroup)?.removeView(state.root)
        emit(Log.DEBUG, "floating bubble detached activity=${activity.javaClass.name}")
    }

    fun observe(webView: WebView) {
        if (!SteamModuleSettings.isSteamDbEnabled(webView.context)) return
        val url = webView.url.orEmpty()
        steamDbPanelController.observe(webView)
        if (observedUrls[webView] == url) return
        observedUrls[webView] = url
        val host = runCatching { url.toUri().host?.lowercase() }.getOrNull()
        emit(
            Log.DEBUG,
            "floating panel observe webView=${identity(webView)} url=${shorten(url)} " +
                    "host=${host ?: "none"} js=${webView.settings.javaScriptEnabled}",
        )
        if (host == STORE_STEAM_HOST) {
            emit(Log.DEBUG, "floating panel store page observed; friend activity script skipped")
            return
        }
        if (host != STEAM_COMMUNITY_HOST && host != "www.$STEAM_COMMUNITY_HOST") {
            emit(Log.DEBUG, "floating panel skip: unsupported host=${host ?: "none"}")
            return
        }
        if (webView.settings.javaScriptEnabled.not()) {
            emit(Log.WARN, "floating panel skip: JavaScript is disabled")
            return
        }
        val activity = webView.context.findActivity()
        if (activity == null) {
            emit(Log.WARN, "floating panel skip: WebView context has no Activity")
            return
        }
        if (hookedWebViews.put(webView, true) == null) {
            webView.addJavascriptInterface(Bridge(this), BRIDGE_NAME)
            emit(
                Log.INFO,
                "floating panel bridge attached webView=${identity(webView)} " +
                        "activity=${activity.javaClass.name}",
            )
        }

        if (injectedUrls[webView] == url) return
        injectedUrls[webView] = url

        // React Native may create the WebView before the page finishes loading.
        // Retrying here also handles profile navigation without relying on text.
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                emit(Log.WARN, "floating panel skip injection: Activity is finishing/destroyed")
                return@post
            }
            inject(webView, url)
        }
    }

    private fun inject(webView: WebView, url: String) {
        emit(Log.DEBUG, "floating panel injecting webView=${identity(webView)} url=${shorten(url)}")
        runCatching {
            webView.evaluateJavascript(INSTALL_SCRIPT) { result ->
                emit(
                    Log.INFO,
                    "floating panel injection result webView=${identity(webView)} result=$result",
                )
            }
        }.onFailure {
            emit(
                Log.ERROR,
                "floating panel injection failed webView=${identity(webView)} " +
                        "error=${it.javaClass.name}: ${it.message}",
            )
        }
    }

    private fun installBubbleTouch(state: FloatingState) {
        state.bubble.setOnClickListener {
            emit(Log.INFO, "floating click callback expanded=${state.expanded}")
            expand(state)
        }
        state.bubble.setOnTouchListener { view, event ->
            val wasDragging = state.dragging
            val handled = handleBubbleTouch(state, event)
            if (event.actionMasked == MotionEvent.ACTION_UP && !wasDragging) {
                emit(Log.INFO, "floating touch tap performClick")
                view.performClick()
            }
            handled
        }
    }

    private fun handleBubbleTouch(state: FloatingState, event: MotionEvent): Boolean {
        if (state.expanded) {
            emit(Log.DEBUG, "floating touch ignored expanded=true action=${event.actionMasked}")
            return false
        }
        val slop = ViewConfiguration.get(state.activity).scaledTouchSlop.toFloat()
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.downX = event.rawX
                state.downY = event.rawY
                state.startLeft = (state.card.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: 0
                state.startTop = (state.card.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: 0
                state.dragging = false
                emit(
                    Log.INFO,
                    "floating touch down raw=${event.rawX},${event.rawY} " +
                            "left=${state.startLeft} top=${state.startTop} slop=$slop",
                )
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - state.downX
                val dy = event.rawY - state.downY
                if (!state.dragging && dx * dx + dy * dy > slop * slop) {
                    state.dragging = true
                    emit(Log.INFO, "floating drag started dx=$dx dy=$dy")
                }
                if (state.dragging) moveBubble(state, state.startLeft + dx.toInt(), state.startTop + dy.toInt())
                true
            }
            MotionEvent.ACTION_UP -> {
                if (state.dragging) {
                    saveBubblePosition(state)
                    snapBubble(state)
                    emit(
                        Log.INFO,
                        "floating touch up drag left=${state.bubbleLeft} top=${state.bubbleTop}",
                    )
                } else {
                    emit(Log.INFO, "floating touch up tap")
                }
                state.dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (state.dragging) snapBubble(state)
                emit(Log.INFO, "floating touch cancel dragging=${state.dragging}")
                state.dragging = false
                true
            }
            else -> true
        }
    }

    private fun placeBubble(state: FloatingState) {
        if (state.root.width == 0 || state.root.height == 0) return
        val size = dp(state.activity, BUBBLE_SIZE_DP)
        val margin = dp(state.activity, BUBBLE_MARGIN_DP)
        val params = FrameLayout.LayoutParams(size, size)
        val maxLeft = (state.root.width - size).coerceAtLeast(0)
        val maxTop = (state.root.height - size).coerceAtLeast(0)
        val savedX = state.activity.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .getFloat(POSITION_X_KEY, DEFAULT_POSITION)
        val savedY = state.activity.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .getFloat(POSITION_Y_KEY, DEFAULT_POSITION)
        if (savedX in 0f..1f && savedY in 0f..1f) {
            params.leftMargin = (maxLeft * savedX).toInt()
            params.topMargin = (maxTop * savedY).toInt()
        } else {
            params.leftMargin = state.root.width - size - margin
            params.topMargin = (state.root.height * 0.28f).toInt().coerceIn(margin, maxTop)
        }
        state.card.layoutParams = params
        state.bubbleLeft = params.leftMargin
        state.bubbleTop = params.topMargin
    }

    private fun moveBubble(state: FloatingState, left: Int, top: Int) {
        val params = state.card.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxLeft = (state.root.width - state.card.width).coerceAtLeast(0)
        val maxTop = (state.root.height - state.card.height).coerceAtLeast(0)
        params.leftMargin = left.coerceIn(0, maxLeft)
        params.topMargin = top.coerceIn(0, maxTop)
        state.card.layoutParams = params
        state.bubbleLeft = params.leftMargin
        state.bubbleTop = params.topMargin
    }

    private fun snapBubble(state: FloatingState) {
        val params = state.card.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = dp(state.activity, BUBBLE_MARGIN_DP)
        val target = if (params.leftMargin + state.card.width / 2 < state.root.width / 2) {
            margin
        } else {
            state.root.width - state.card.width - margin
        }
        val start = params.leftMargin
        val animator = ValueAnimator.ofInt(start, target).apply {
            duration = 220L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        }
        state.animator = animator
        animator.addUpdateListener {
            params.leftMargin = it.animatedValue as Int
            state.card.layoutParams = params
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                state.bubbleLeft = target
                saveBubblePosition(state)
                if (state.animator === animator) state.animator = null
            }
        })
        animator.start()
    }

    private fun expand(state: FloatingState) = morph(state, true)

    private fun collapse(state: FloatingState) = morph(state, false)

    private fun positionSharedIcon(state: FloatingState, opening: Boolean, progress: Float) {
        val iconSize = dp(state.activity, PANEL_ICON_SIZE_DP)
        val bubbleSize = dp(state.activity, BUBBLE_SIZE_DP)
        val bubbleOffset = (bubbleSize - iconSize) / 2
        val panelOffsetLeft = dp(state.activity, PANEL_PADDING_HORIZONTAL_DP)
        val panelOffsetTop = dp(state.activity, PANEL_PADDING_TOP_DP)
        val left = lerp(
            if (opening) bubbleOffset else panelOffsetLeft,
            if (opening) panelOffsetLeft else bubbleOffset,
            progress,
        )
        val top = lerp(
            if (opening) bubbleOffset else panelOffsetTop,
            if (opening) panelOffsetTop else bubbleOffset,
            progress,
        )
        val params = state.sharedIcon.layoutParams as? FrameLayout.LayoutParams ?: return
        params.leftMargin = left
        params.topMargin = top
        params.width = iconSize
        params.height = iconSize
        state.sharedIcon.layoutParams = params
        val panelProgress = if (opening) progress else 1f - progress
        (state.sharedIcon.background as? GradientDrawable)?.setColor(
            ((255 * panelProgress).toInt() shl 24) or (SteamDbUi.CARD and 0x00ffffff),
        )
    }

    private fun refreshSharedIcon(state: FloatingState) {
        val drawable = loadIcon(state.activity)
        state.sharedIcon.setImageDrawable(drawable)
        state.sharedIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        state.sharedIcon.alpha = 1f
        state.sharedIcon.requestLayout()
        state.sharedIcon.invalidate()
        emit(
            Log.INFO,
            "floating panel shared icon view refreshed " +
                    "drawable=${drawable?.javaClass?.name ?: "none"}",
        )
        state.card.post { logIconLayout(state, "refreshed") }
    }

    private fun logIconLayout(state: FloatingState, phase: String) {
        logIconLayout(state.sharedIcon, state, phase)
    }

    private fun logIconLayout(icon: ImageView, state: FloatingState, phase: String) {
        val bounds = Rect()
        val visible = icon.getGlobalVisibleRect(bounds)
        val params = icon.layoutParams as? FrameLayout.LayoutParams
        val drawable = icon.drawable
        emit(
            Log.INFO,
            "floating panel shared icon view phase=$phase " +
                    "visible=$visible visibility=${icon.visibility} alpha=${icon.alpha} " +
                    "size=${icon.width}x${icon.height} " +
                    "margin=${params?.leftMargin ?: 0},${params?.topMargin ?: 0} " +
                    "z=${icon.z} elevation=${icon.elevation} translationZ=${icon.translationZ} " +
                    "global=$bounds drawable=${drawable?.javaClass?.name ?: "none"} " +
                    "intrinsic=${drawable?.intrinsicWidth ?: 0}x${drawable?.intrinsicHeight ?: 0} " +
                    "drawableBounds=${drawable?.bounds} card=${state.card.width}x${state.card.height} " +
                    "panelElevation=${state.panel.elevation}",
        )
    }

    private fun morph(state: FloatingState, opening: Boolean) {
        if (state.animator?.isRunning == true || state.expanded == opening) return
        if (state.root.width == 0 || state.root.height == 0) return
        val bubbleSize = dp(state.activity, BUBBLE_SIZE_DP)
        val safe = ViewCompat.getRootWindowInsets(state.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val safeLeft = safe?.left ?: 0
        val safeTop = safe?.top ?: 0
        val safeWidth = state.root.width - safeLeft - (safe?.right ?: 0)
        val safeHeight = state.root.height - safeTop - (safe?.bottom ?: 0)
        val panelWidth = min(safeWidth - dp(state.activity, 32), dp(state.activity, 400))
            .coerceAtLeast(bubbleSize)
        val desiredHeight = state.statsPanel.view.heightForWidth(panelWidth)
        val panelHeight = min(desiredHeight, safeHeight - dp(state.activity, 32)).coerceAtLeast(bubbleSize)
        val startParams = state.card.layoutParams as? FrameLayout.LayoutParams ?: return
        val startLeft = startParams.leftMargin
        val startTop = startParams.topMargin
        val startWidth = state.card.width
        val startHeight = state.card.height
        val endLeft = if (opening) safeLeft + (safeWidth - panelWidth) / 2 else state.bubbleLeft
        val endTop = if (opening) safeTop + (safeHeight - panelHeight) / 2 else state.bubbleTop
        val endWidth = if (opening) panelWidth else bubbleSize
        val endHeight = if (opening) panelHeight else bubbleSize
        emit(
            Log.INFO,
            "floating panel icon transform start=${if (opening) "expand" else "collapse"} " +
                    "card=${startWidth}x${startHeight}@${startLeft},${startTop} " +
                    "target=${endWidth}x${endHeight}@${endLeft},${endTop}",
        )
        logIconLayout(state, if (opening) "expand-start" else "collapse-start")
        if (opening) {
            state.panel.alpha = 0f
            state.panel.visibility = View.VISIBLE
            state.scrim.visibility = View.VISIBLE
            state.scrim.isClickable = true
            steamDbPanelController.refresh(state.activity, state.statsPanel)
        }
        if (!opening) steamDbPanelController.close(state.statsPanel)
        state.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MORPH_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val raw = it.animatedValue as Float
                val params = state.card.layoutParams as FrameLayout.LayoutParams
                params.leftMargin = lerp(startLeft, endLeft, raw)
                params.topMargin = lerp(startTop, endTop, raw)
                params.width = lerp(startWidth, endWidth, raw)
                params.height = lerp(startHeight, endHeight, raw)
                state.card.layoutParams = params
                val background = state.card.background as? GradientDrawable
                val cornerRadius = lerp(
                    if (opening) dp(state.activity, BUBBLE_SIZE_DP / 2).toFloat() else dp(state.activity, PANEL_RADIUS_DP).toFloat(),
                    if (opening) dp(state.activity, PANEL_RADIUS_DP).toFloat() else dp(state.activity, BUBBLE_SIZE_DP / 2).toFloat(),
                    raw,
                )
                val shapeProgress = if (opening) raw else 1f - raw
                background?.cornerRadius = cornerRadius
                background?.setColor(interpolateColor(shapeProgress))
                (state.card.foreground as? SteamFloatingBorder)?.update(cornerRadius, shapeProgress)
                state.card.elevation = lerp(dp(state.activity, 6).toFloat(), dp(state.activity, 10).toFloat(), shapeProgress)
                state.card.invalidateOutline()
                val visibleProgress = if (opening) raw else 1f - raw
                state.scrim.alpha = visibleProgress * SCRIM_ALPHA
                state.panel.alpha = ((visibleProgress - PANEL_CONTENT_DELAY) / (1f - PANEL_CONTENT_DELAY)).coerceIn(0f, 1f)
                positionSharedIcon(state, opening, raw)
                state.sharedIcon.alpha = 1f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    state.animator = null
                    state.expanded = opening
                    if (opening) {
                        state.sharedIcon.alpha = 1f
                        state.panel.alpha = 1f
                        state.card.post { logIconLayout(state, "expand-end") }
                        emit(Log.INFO, "floating panel shown width=$panelWidth height=$panelHeight")
                    } else {
                        state.panel.visibility = View.GONE
                        state.scrim.visibility = View.GONE
                        state.scrim.isClickable = false
                        state.sharedIcon.alpha = 1f
                        state.card.post { logIconLayout(state, "collapse-end") }
                        emit(Log.INFO, "floating panel collapsed")
                    }
                }
            })
        }
        state.animator?.start()
    }

    private fun buildBubble(activity: Activity): TouchFrameLayout = TouchFrameLayout(activity).apply {
        tag = BUBBLE_TAG
        contentDescription = "Millennium"
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun buildIconView(activity: Activity, drawable: Drawable?): ImageView = ImageView(activity).apply {
        tag = SHARED_ICON_TAG
        contentDescription = "Millennium"
        setImageDrawable(drawable)
        imageTintList = ColorStateList.valueOf(SteamDbUi.TEXT)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(activity, 8).toFloat()
        }
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        translationZ = dp(activity, ICON_Z_OFFSET_DP).toFloat()
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun cardBackground(activity: Activity): GradientDrawable = GradientDrawable().apply {
        setColor(BUBBLE_COLOR)
        cornerRadius = dp(activity, BUBBLE_SIZE_DP / 2).toFloat()
    }

    private fun roundedCardOutline(activity: Activity): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val radius = (view.background as? GradientDrawable)?.cornerRadius
                ?: if (view.width <= dp(activity, BUBBLE_SIZE_DP + 1)) {
                    view.width / 2f
                } else {
                    dp(activity, PANEL_RADIUS_DP).toFloat()
                }
            outline.setRoundRect(0, 0, view.width, view.height, radius.coerceAtLeast(0f))
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    @Suppress("DEPRECATION")
    private fun moduleResources(activity: Activity): Resources? {
        val path = moduleApkPath
        if (path.isNullOrBlank()) {
            emit(Log.WARN, "floating panel module APK resource path unavailable")
            return null
        }
        synchronized(moduleResourcesLock) {
            moduleResourcesBundle?.let { bundle ->
                if (bundle.path == path) return bundle.resources
            }
            val assetManager: AssetManager = try {
                AssetManager::class.java.getDeclaredConstructor().apply {
                    isAccessible = true
                }.newInstance()
            } catch (error: Throwable) {
                emit(
                    Log.ERROR,
                    "floating panel module AssetManager creation failed path=$path " +
                            "error=${error.javaClass.simpleName}:${error.message}",
                )
                return null
            }
            val cookie: Int = try {
                val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                    "addAssetPath",
                    String::class.java,
                ).apply {
                    isAccessible = true
                }
                (addAssetPath.invoke(assetManager, path) as? Number)?.toInt() ?: 0
            } catch (error: Throwable) {
                emit(
                    Log.ERROR,
                    "floating panel module APK asset path failed path=$path " +
                            "error=${error.javaClass.simpleName}:${error.message}",
                )
                0
            }
            if (cookie == 0) {
                emit(Log.ERROR, "floating panel module APK asset path rejected path=$path")
                return null
            }
            val resources = Resources(
                assetManager,
                activity.resources.displayMetrics,
                activity.resources.configuration,
            )
            moduleResourcesBundle = ModuleResourcesBundle(path, resources)
            emit(Log.INFO, "floating panel module APK resources loaded path=$path cookie=$cookie")
            return resources
        }
    }

    private fun loadIcon(activity: Activity): Drawable? =
        loadIconResource(activity, R.drawable.ic_millennium_floating)

    private fun loadIconResource(activity: Activity, resourceId: Int): Drawable? {
        val resources = moduleResources(activity) ?: return null
        return runCatching {
            ResourcesCompat.getDrawable(resources, resourceId, activity.theme)
        }.onFailure { error ->
            emit(
                Log.ERROR,
                "floating panel shared icon load failed source=moduleApk " +
                        "path=$moduleApkPath resourceId=0x${resourceId.toString(16)} " +
                        "error=${error.javaClass.simpleName}:${error.message}",
            )
        }.getOrNull()?.also {
            emit(
                Log.INFO,
                "floating panel shared icon resource loaded source=moduleApk " +
                        "path=$moduleApkPath resourceId=0x${resourceId.toString(16)}",
            )
        }
    }

    private data class ModuleResourcesBundle(
        val path: String,
        val resources: Resources,
    )

    private fun saveBubblePosition(state: FloatingState) {
        val maxLeft = (state.root.width - state.card.width).coerceAtLeast(1)
        val maxTop = (state.root.height - state.card.height).coerceAtLeast(1)
        state.activity.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE).edit {
            putFloat(POSITION_X_KEY, state.bubbleLeft.toFloat() / maxLeft)
            putFloat(POSITION_Y_KEY, state.bubbleTop.toFloat() / maxTop)
        }
    }

    private class TouchFrameLayout(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                Log.i(
                    "MillenniumSteamUI",
                    "floating dispatch tag=$tag action=${event.actionMasked} " +
                            "x=${event.rawX} y=${event.rawY} clickable=$isClickable enabled=$isEnabled",
                )
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private class FloatingState(
        val activity: Activity,
        val root: FrameLayout,
        val scrim: View,
        val card: FrameLayout,
        val bubble: View,
        val panel: View,
        val sharedIcon: ImageView,
        val statsPanel: SteamDbPanelController.Panel,
    ) {
        var animator: ValueAnimator? = null
        var expanded = false
        var dragging = false
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var bubbleLeft = 0
        var bubbleTop = 0
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).toInt()

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun interpolateColor(fraction: Float): Int {
        val start = BUBBLE_COLOR
        val end = PANEL_COLOR
        val t = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * t).toInt()
        val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * t).toInt()
        val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * t).toInt()
        val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private class Bridge(
        private val feature: SteamFloatingPanelFeature,
    ) {
        @JavascriptInterface
        @Suppress("unused")
        fun logEvent(message: String) {
            feature.emit(Log.INFO, "floating panel JS: ${shorten(message)}")
        }
    }

    private companion object {
        const val BRIDGE_NAME = "MillenniumBridge"
        const val ROOT_TAG = "millennium.floating.panel.root"
        const val CARD_TAG = "millennium.floating.panel.card"
        const val BUBBLE_TAG = "millennium.floating.panel.bubble"
        const val SHARED_ICON_TAG = "millennium.floating.panel.shared-icon"
        const val STEAM_COMMUNITY_HOST = "steamcommunity.com"
        const val STORE_STEAM_HOST = "store.steampowered.com"
        const val BUBBLE_SIZE_DP = 42
        const val PANEL_ICON_SIZE_DP = SteamDbUi.ICON_SIZE
        const val PANEL_PADDING_HORIZONTAL_DP = SteamDbUi.PADDING
        const val PANEL_PADDING_TOP_DP = SteamDbUi.PADDING + (SteamDbUi.HEADER_HEIGHT - SteamDbUi.ICON_SIZE) / 2
        const val BUBBLE_MARGIN_DP = 18
        const val PANEL_RADIUS_DP = SteamDbUi.RADIUS
        const val PANEL_CONTENT_DELAY = 0.22f
        const val ICON_Z_OFFSET_DP = 16
        const val ICON_REFRESH_DELAY_MS = 250L
        const val MORPH_DURATION_MS = 380L
        const val SCRIM_ALPHA = 0.72f
        const val BUBBLE_COLOR = SteamDbUi.CARD
        const val PANEL_COLOR = SteamDbUi.PANEL
        const val POSITION_PREFS = "millennium_floating_position"
        const val POSITION_X_KEY = "x_fraction"
        const val POSITION_Y_KEY = "y_fraction"
        const val DEFAULT_POSITION = -1f

        // Steam's profile activity links use a stable URL path across localizations.
        const val INSTALL_SCRIPT = """
(function() {
  var hookVersion = 'v4';
  function bridgeLog(message) {
    if (window.MillenniumBridge && window.MillenniumBridge.logEvent) {
      window.MillenniumBridge.logEvent(message);
    }
  }
  function isFriendActivity(anchor) {
    var href = anchor && anchor.getAttribute('href');
    if (!href) return false;
    try {
      var path = new URL(href, window.location.href).pathname.toLowerCase();
      return /\/(?:my|profiles\/[^/]+)\/home\/?$/.test(path);
    } catch (ignored) { return false; }
  }
  function dumpDom() {
    if (window.__millenniumDomDumped) return;
    var candidates = document.querySelectorAll('a');
    var clickable = document.querySelectorAll('a,button,[role="button"],[onclick],[data-featuretarget]');
    if (candidates.length === 0 && clickable.length === 0) {
      window.__millenniumDomDumpRetries = (window.__millenniumDomDumpRetries || 0) + 1;
      if (window.__millenniumDomDumpRetries <= 12) {
        setTimeout(dumpDom, 500);
      } else {
        bridgeLog('DOM dump gave up after retries url=' + window.location.href);
      }
      return;
    }
    var candidateCount = 0;
    for (var i = 0; i < candidates.length; i++) {
      if (isFriendActivity(candidates[i])) candidateCount++;
    }
    bridgeLog(
      'installed url=' + window.location.href + ' anchors=' + candidates.length +
      ' friendActivityCandidates=' + candidateCount
    );
    var visibleCount = 0;
    for (var c = 0; c < clickable.length; c++) {
      var node = clickable[c];
      var rect = node.getBoundingClientRect();
      if (rect.width < 20 || rect.height < 20 || rect.bottom < 0 || rect.top > window.innerHeight) continue;
      visibleCount++;
      if (visibleCount > 80) continue;
      var nodeText = (node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80);
      var nodeClass = typeof node.className === 'string' ? node.className.slice(0, 100) : '';
      bridgeLog(
        'clickable tag=' + node.tagName + ' id=' + (node.id || '') +
        ' class=' + nodeClass + ' href=' + (node.href || node.getAttribute('href') || '') +
        ' rect=' + Math.round(rect.left) + ',' + Math.round(rect.top) + ',' +
        Math.round(rect.width) + 'x' + Math.round(rect.height) + ' text=' + nodeText
      );
    }
    bridgeLog('clickable visible=' + visibleCount + ' total=' + clickable.length);
    window.__millenniumDomDumped = true;
  }
  if (window.__millenniumFriendActivityHook === hookVersion) {
    bridgeLog('already-installed url=' + window.location.href);
    dumpDom();
    return 'already-installed';
  }
  window.__millenniumFriendActivityHook = hookVersion;
  var lastClickLog = 0;
  document.addEventListener('click', function(event) {
    var target = event.target;
    var element = target && target.closest ?
      target.closest('a,button,[role="button"],[onclick],div') : target;
    if (!element) return;
    var now = Date.now();
    if (now - lastClickLog < 250) return;
    lastClickLog = now;
    var rect = element.getBoundingClientRect();
    var elementText = (element.innerText || element.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80);
    var elementClass = typeof element.className === 'string' ? element.className.slice(0, 100) : '';
    bridgeLog(
      'click observed tag=' + element.tagName + ' id=' + (element.id || '') +
      ' class=' + elementClass + ' href=' + (element.href || element.getAttribute('href') || '') +
      ' rect=' + Math.round(rect.left) + ',' + Math.round(rect.top) + ',' +
      Math.round(rect.width) + 'x' + Math.round(rect.height) + ' text=' + elementText
    );
  }, true);
  setTimeout(dumpDom, 300);
  return 'installed';
})();
        """

        fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        fun Context.findActivity(): Activity? {
            var current: Context? = this
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return null
        }

        fun identity(value: Any): String = Integer.toHexString(System.identityHashCode(value))

        fun shorten(value: String): String = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(300)
    }
}
