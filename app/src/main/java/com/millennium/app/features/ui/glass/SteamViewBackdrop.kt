package com.millennium.app.features.ui.glass

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.PixelCopy
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.core.graphics.createBitmap
import com.kyant.backdrop.Backdrop

/** Samples the host without putting WebView's draw functor inside a glass shader. */
@RequiresApi(33)
internal class SteamViewBackdrop(private val source: View) : Backdrop, AutoCloseable {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val captureRect = Rect()
    private val displayedRect = Rect()
    private val sourceMatrix = Matrix()
    private val targetMatrix = Matrix()
    private val inverse = Matrix()
    private val location = IntArray(2)
    private var generation by mutableIntStateOf(0)
    private val node = RenderNode("Millennium background capture")
    private val renderer = HardwareRenderer()
    private val worker = HandlerThread("MillenniumGlassCapture").apply { start() }
    private val handler = Handler(worker.looper)
    // Publish completed pixels before a pending traversal, even when ViewRootImpl
    // has installed a synchronization barrier for that frame.
    private val main = Handler.createAsync(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var reader: ImageReader? = null
    private var pending = false
    private var closed = false
    private var failure: Exception? = null
    private var nextCaptureFrameNanos = 0L
    private var wakeScheduled = false
    // Accessed only on the capture worker. Keep the Surface alive through PixelCopy.
    private var copying = false
    private var pendingDisposal: (() -> Unit)? = null
    private val wake = Choreographer.FrameCallback {
        wakeScheduled = false
        // This runs in the animation phase: invalidate directly so the traversal
        // can capture in this frame, without another postInvalidateOnAnimation hop.
        if (!closed) source.rootView.invalidate()
    }
    private val timeout = Runnable {
        if (!closed && pending) {
            failure = IllegalStateException("Background capture timed out")
            needsCapture = true
            requestFrame()
        }
    }

    var ready = false
        private set
    var needsCapture = false
        private set

    override val isCoordinatesDependent = true

    fun record(): Boolean {
        failure?.let { throw it }
        if (closed || source.width <= 0 || source.height <= 0) return false
        needsCapture = true
        if (pending) return false
        // record() is called by the host's pre-draw callback, after this frame's
        // layout. Use the public monotonic clock; Choreographer's frame-time getter
        // is not part of Android's app API.
        val frameTime = System.nanoTime()
        if (frameTime + FRAME_TOLERANCE_NS < nextCaptureFrameNanos) {
            requestFrame()
            return false
        }
        choreographer.removeFrameCallback(wake)
        wakeScheduled = false
        updateSourceMatrix()
        // Keep the same viewport/scale as the visible host. Drawing WebView at a
        // second size can make Chromium continuously invalidate both renderers.
        // Only PixelCopy crops and downsamples the result.
        val captureTop = (source.height - 220 * source.resources.displayMetrics.density).coerceAtLeast(0f)
        val area = RectF(0f, captureTop, source.width.toFloat(), source.height.toFloat())
        sourceMatrix.mapRect(area)
        area.roundOut(captureRect)
        val width = source.rootView.width
        val height = source.rootView.height
        if (!captureRect.intersect(0, 0, width, height)) { needsCapture = false; return false }
        prepareSurface(width, height)
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording()
        try {
            canvas.drawColor(0xff171a21.toInt())
            canvas.concat(sourceMatrix)
            // All View access stays on the UI thread; this records hardware commands.
            source.draw(canvas)
        } finally { node.endRecording() }
        needsCapture = false
        pending = true
        // Keep the 60 Hz phase across 90/120 Hz display frames. After a long idle
        // or slow capture, restart from this frame instead of queuing catch-up work.
        nextCaptureFrameNanos = if (frameTime - nextCaptureFrameNanos > CAPTURE_INTERVAL_NS) {
            frameTime + CAPTURE_INTERVAL_NS
        } else {
            nextCaptureFrameNanos + CAPTURE_INTERVAL_NS
        }
        renderer.setContentRoot(node)
        val result = renderer.createRenderRequest().setWaitForPresent(false).syncAndDraw()
        if (result and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or HardwareRenderer.SYNC_CONTEXT_IS_STOPPED) != 0) {
            error("Background renderer unavailable: $result")
        }
        if (result and HardwareRenderer.SYNC_FRAME_DROPPED != 0) {
            pending = false
            needsCapture = true
            requestFrame()
        } else {
            main.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        }
        return true
    }

    private fun requestFrame() {
        if (closed || wakeScheduled) return
        wakeScheduled = true
        choreographer.postFrameCallback(wake)
    }

    private fun prepareSurface(width: Int, height: Int) {
        if (reader?.width == width && reader?.height == height) return
        // No frame is in flight when this runs, including during window resizing.
        renderer.setSurface(null)
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        reader = next
        next.setOnImageAvailableListener({ images ->
            val image = try {
                images.acquireLatestImage() ?: return@setOnImageAvailableListener
            } catch (exception: IllegalStateException) {
                main.post {
                    if (!closed) { failure = exception; needsCapture = true; requestFrame() }
                }
                return@setOnImageAvailableListener
            }
            val rect = Rect(captureRect)
            val pixels = createBitmap((rect.width() + 1) / 2, (rect.height() + 1) / 2)
            copying = true
            try {
                // The source Surface contains only Steam, never the glass overlay.
                // The GPU readback is asynchronous and limited to the lower strip.
                PixelCopy.request(images.surface, rect, pixels, { result ->
                    image.close()
                    copying = false
                    val error = if (result == PixelCopy.SUCCESS) null
                    else IllegalStateException("Background pixel copy failed: $result")
                    publish(pixels, rect, error)
                    pendingDisposal?.invoke()
                }, handler)
            } catch (exception: Exception) {
                image.close()
                copying = false
                publish(pixels, rect, exception)
            }
        }, handler)
        renderer.setSurface(next.surface)
    }

    private fun publish(pixels: Bitmap, rect: Rect, error: Exception?) {
        main.post {
            if (closed) { pixels.recycle(); return@post }
            main.removeCallbacks(timeout)
            pending = false
            failure = error
            if (error == null) {
                bitmap = pixels
                displayedRect.set(rect)
                ready = true
                generation++
            } else {
                pixels.recycle()
                needsCapture = true // Let the session handle the error and restore Steam.
            }
            // Publish as soon as the copy is ready. Scheduling another animation
            // callback here would make the background wait an extra display frame.
            source.rootView.invalidate()
        }
    }

    private fun updateSourceMatrix() {
        sourceMatrix.reset()
        source.transformMatrixToGlobal(sourceMatrix)
        val rootMatrix = Matrix()
        source.rootView.transformMatrixToGlobal(rootMatrix)
        val origin = floatArrayOf(0f, 0f)
        rootMatrix.mapPoints(origin)
        source.rootView.getLocationInWindow(location)
        sourceMatrix.postTranslate(location[0] - origin[0], location[1] - origin[1])
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        // Observe the frame counter even when the sampled bitmap instance is reused.
        if (generation == 0 || !ready || coordinates == null || !coordinates.isAttached) return
        val origin = coordinates.localToWindow(Offset.Zero)
        val dx = coordinates.localToWindow(Offset(1f, 0f)) - origin
        val dy = coordinates.localToWindow(Offset(0f, 1f)) - origin
        targetMatrix.setValues(floatArrayOf(dx.x, dy.x, origin.x, dx.y, dy.y, origin.y, 0f, 0f, 1f))
        if (!targetMatrix.invert(inverse)) return
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        try {
            canvas.concat(inverse)
            bitmap?.let { canvas.drawBitmap(it, null, displayedRect, paint) }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        bitmap = null
        choreographer.removeFrameCallback(wake)
        wakeScheduled = false
        main.removeCallbacks(timeout)
        renderer.destroy()
        node.discardDisplayList()
        val oldReader = reader
        reader = null
        oldReader?.setOnImageAvailableListener(null, null)
        // An already running readback owns its Image/Surface until its copy finishes.
        handler.post {
            val dispose = { oldReader?.close(); worker.quitSafely(); Unit }
            if (copying) pendingDisposal = dispose else dispose()
        }
    }

    private companion object {
        const val CAPTURE_INTERVAL_NS = 1_000_000_000L / 60
        const val FRAME_TOLERANCE_NS = 500_000L
        const val CAPTURE_TIMEOUT_MS = 1_000L
    }
}
