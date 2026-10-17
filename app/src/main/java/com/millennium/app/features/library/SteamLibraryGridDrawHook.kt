package com.millennium.app.features.library

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Nagram's SharedPhotoVideoCell2.drawCrossafadeImage, adapted to retained Steam portraits:
 * draw the existing target view into the transitioning slot, without creating an image loader.
 * React Native owns slot geometry/opacity; this hook only supplies the existing drawing.
 */
internal class SteamLibraryGridDrawHook {
    private val sources = WeakHashMap<View, WeakReference<View>>()

    fun install(module: XposedModule, loader: ClassLoader) {
        val type = Class.forName("com.facebook.react.views.view.ReactViewGroup", false, loader)
        val draw = type.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        module.hook(draw).setId("millennium.library.draw.crossfade").intercept { chain ->
            val slot = chain.thisObject as? View
            val tag = slot?.tag as? String
            val canvas = chain.args.firstOrNull() as? Canvas
            if (slot == null || canvas == null || tag == null || !tag.startsWith(DRAW_PREFIX)) {
                return@intercept chain.proceed()
            }
            // The tagged slot has no children. All other RN views keep their normal drawing.
            val saved = canvas.save()
            try {
                val host = findHost(slot)
                val sourceTag = SOURCE_PREFIX + tag.removePrefix(DRAW_PREFIX)
                var source = sources[slot]?.get()
                if (source == null || source.tag != sourceTag || !source.isAttachedToWindow ||
                    findHost(source) !== host
                ) {
                    source = host?.findViewWithTag(sourceTag)
                    if (source != null) sources[slot] = WeakReference(source)
                }
                if (source != null && source !== slot && source.width > 0 && source.height > 0) {
                    canvas.clipRect(0, 0, slot.width, slot.height)
                    canvas.scale(slot.width.toFloat() / source.width, slot.height.toFloat() / source.height)
                    // Draw the untransformed inner portrait. The original cell can be transparent
                    // or off-screen; neither its outer scale nor its alpha is copied a second time.
                    source.draw(canvas)
                }
            } catch (_: Throwable) {
                // A failed temporary drawing must not interrupt Steam's rendering.
            } finally {
                canvas.restoreToCount(saved)
            }
            // A native Animated opacity/transform can reuse a display list without redrawing it.
            // Refresh this temporary drawing while mounted so an image finishing its normal load
            // is reflected immediately. No timer, bitmap allocation or JS callback is involved.
            if (slot.isAttachedToWindow) slot.postInvalidateOnAnimation()
            null
        }
        available = true
    }

    private fun findHost(view: View): ViewGroup? {
        var parent = view.parent
        while (parent is View) {
            if (parent.tag == HOST_TAG) return parent as? ViewGroup
            parent = parent.parent
        }
        return null
    }

    companion object {
        @Volatile
        var available = false
            private set

        private const val HOST_TAG = "millennium.library.grid.covers.v1"
        private const val SOURCE_PREFIX = "millennium.library.cover.source."
        private const val DRAW_PREFIX = "millennium.library.cover.draw."
    }
}
