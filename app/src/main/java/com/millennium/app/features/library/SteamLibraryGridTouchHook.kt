package com.millennium.app.features.library

import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.hypot

/** Gives our library pinch the touch stream as soon as the second finger arrives, like Nagram. */
internal class SteamLibraryGridTouchHook {
    private data class ScrollPeer(
        val handler: WeakReference<Any>,
        val scroll: WeakReference<View>,
        val downTime: Long,
    )
    private data class Pinch(
        val firstId: Int,
        val secondId: Int,
        val distance: Double,
        var scale: Double,
        var time: Long,
    )

    // Gesture Handler calls these hooks on the UI thread. Values must not keep their weak keys alive.
    private val scrollPeers = WeakHashMap<View, ScrollPeer>()
    private val pinches = WeakHashMap<Any, Pinch>()

    fun install(module: XposedModule, loader: ClassLoader) {
        val base = Class.forName("com.swmansion.gesturehandler.core.GestureHandler", false, loader)
        val pinchType = Class.forName("com.swmansion.gesturehandler.core.PinchGestureHandler", false, loader)
        val scrollType = Class.forName("com.swmansion.gesturehandler.core.NativeViewGestureHandler", false, loader)
        val getView = base.getMethod("getView")
        val getState = base.getMethod("getState")
        val activate = pinchType.getMethod("activate", Boolean::class.javaPrimitiveType)
        val end = base.getMethod("end")
        val cancel = base.getMethod("cancel")
        val scale = pinchType.getDeclaredField("scale").apply { isAccessible = true }
        val velocity = pinchType.getDeclaredField("velocity").apply { isAccessible = true }
        val pinchHandle = pinchType.getDeclaredMethod("onHandle", MotionEvent::class.java, MotionEvent::class.java)
        val scrollHandle = scrollType.getDeclaredMethod("onHandle", MotionEvent::class.java, MotionEvent::class.java)
        val reset = pinchType.getDeclaredMethod("onReset")

        module.hook(scrollHandle).setId("millennium.library.touch.scroll-peer").intercept { chain ->
            val event = chain.args.firstOrNull() as? MotionEvent
            if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                runCatching {
                    val handler = chain.thisObject ?: return@runCatching
                    val view = getView.invoke(handler) as? View ?: return@runCatching
                    val grid = findGrid(view) ?: return@runCatching
                    // FlatList may expose a RefreshControl wrapper as its first native view.
                    val scroll = view.findViewWithTag<View>(SCROLL_TAG) ?: return@runCatching
                    scrollPeers[grid] = ScrollPeer(WeakReference(handler), WeakReference(scroll), event.downTime)
                }
            }
            chain.proceed()
        }

        module.hook(pinchHandle).setId("millennium.library.touch.pinch").intercept { chain ->
            // Keep native focal-point transforms and dispatch to Animated on the UI thread.
            val result = chain.proceed()
            runCatching {
                val handler = chain.thisObject ?: return@runCatching
                val view = getView.invoke(handler) as? View ?: return@runCatching
                if (view.tag != GRID_TAG) return@runCatching
                val event = chain.args.getOrNull(1) as? MotionEvent ?: return@runCatching
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN) pinches.remove(handler)
                var pinch = pinches[handler]
                if (pinch == null && action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2 &&
                    getState.invoke(handler) == BEGAN
                ) {
                    val distance = distance(event, 0, 1)
                    if (distance < 1.0) return@runCatching
                    // No simultaneity timeout: a second finger can take over an existing scroll.
                    activate.invoke(handler, false)
                    if (getState.invoke(handler) != ACTIVE) return@runCatching
                    pinch = Pinch(event.getPointerId(0), event.getPointerId(1), distance, 1.0, event.eventTime)
                    pinches[handler] = pinch
                    val peer = scrollPeers[view]?.takeIf { it.downTime == event.downTime }
                    val scrollHandler = peer?.handler?.get()
                    if (scrollHandler != null && getState.invoke(scrollHandler) in listOf(UNDETERMINED, BEGAN, ACTIVE)) {
                        // NativeViewGestureHandler.onCancel delivers ACTION_CANCEL to the ScrollView.
                        cancel.invoke(scrollHandler)
                    }
                    peer?.scroll?.get()?.let { scroll ->
                        // A handler attached to RefreshControl only cancels that wrapper. Stop the
                        // actual ScrollView's drag too, without synthesizing another DOWN or click.
                        val touchCancel = MotionEvent.obtain(event)
                        try {
                            touchCancel.action = MotionEvent.ACTION_CANCEL
                            scroll.onTouchEvent(touchCancel)
                        } finally {
                            touchCancel.recycle()
                        }
                    }
                }
                if (pinch != null) {
                    val first = event.findPointerIndex(pinch.firstId)
                    val second = event.findPointerIndex(pinch.secondId)
                    if (action == MotionEvent.ACTION_CANCEL || event.pointerCount != 2 || first < 0 || second < 0) {
                        pinches.remove(handler)
                        if (getState.invoke(handler) == ACTIVE) cancel.invoke(handler)
                        return@runCatching
                    }
                    // Bypass Android's additional pixel-distance threshold. The JS machine still
                    // applies Nagram's 1% dead zone, using Euclidean distance in every direction.
                    val next = distance(event, first, second) / pinch.distance
                    if (next.isFinite() && next > 0.0) {
                        val elapsed = event.eventTime - pinch.time
                        scale.setDouble(handler, next)
                        velocity.setDouble(handler, if (elapsed > 0) (next - pinch.scale) * 1000 / elapsed else 0.0)
                        pinch.scale = next
                        pinch.time = event.eventTime
                    }
                    if (action == MotionEvent.ACTION_POINTER_UP) {
                        pinches.remove(handler)
                        // Lifting either finger completes the pinch; it is not a cancelled gesture.
                        if (getState.invoke(handler) == ACTIVE) end.invoke(handler)
                    }
                }
            }
            result
        }

        module.hook(reset).setId("millennium.library.touch.reset").intercept { chain ->
            pinches.remove(chain.thisObject)
            chain.proceed()
        }
    }

    private fun distance(event: MotionEvent, first: Int, second: Int): Double = hypot(
        (event.getX(second) - event.getX(first)).toDouble(),
        (event.getY(second) - event.getY(first)).toDouble(),
    )

    private fun findGrid(view: View): View? {
        var current: View? = view
        repeat(12) {
            if (current?.tag == GRID_TAG) return current
            current = current?.parent as? View ?: return null
        }
        return null
    }

    private companion object {
        const val GRID_TAG = "millennium.library.grid.pinch.v1"
        const val SCROLL_TAG = "millennium.library.grid.scroll.v1"
        const val UNDETERMINED = 0
        const val BEGAN = 2
        const val ACTIVE = 4
    }
}
