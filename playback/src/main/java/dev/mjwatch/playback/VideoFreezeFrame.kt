package dev.mjwatch.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.ImageView

/** Keeps a small copy of the video underneath the controls if the decoder clears its surface. */
internal class VideoFreezeFrame(context: Context) : ImageView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var frame: Bitmap? = null
    private var scratch: Bitmap? = null
    private var copying = false
    private var generation = 0
    private var waitingForFirstFrame = false

    init {
        scaleType = ScaleType.FIT_XY
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    fun sample(surface: SurfaceView) {
        if (copying || visibility == VISIBLE || waitingForFirstFrame || !surface.isShown ||
            !surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return

        val scale = minOf(1f, 640f / maxOf(surface.width, surface.height))
        val width = (surface.width * scale).toInt().coerceAtLeast(1)
        val height = (surface.height * scale).toInt().coerceAtLeast(1)
        val bitmap = scratch?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val requestGeneration = generation
        copying = true
        try {
            PixelCopy.request(surface, bitmap, { result ->
                copying = false
                if (result == PixelCopy.SUCCESS && requestGeneration == generation) {
                    scratch = frame
                    frame = bitmap
                } else {
                    scratch = bitmap
                }
            }, handler)
        } catch (_: IllegalArgumentException) {
            copying = false // The surface can disappear between the validity check and the request.
        }
    }

    fun hold(waitForFirstFrame: Boolean = false) {
        waitingForFirstFrame = waitingForFirstFrame || waitForFirstFrame
        generation++
        frame?.let {
            setImageBitmap(it)
            visibility = VISIBLE
        }
    }

    fun onReady() {
        if (!waitingForFirstFrame) showLiveVideo()
    }

    fun showLiveVideo() {
        waitingForFirstFrame = false
        visibility = GONE
        setImageDrawable(null)
    }

    fun clear() {
        generation++
        frame = null
        showLiveVideo()
    }
}
