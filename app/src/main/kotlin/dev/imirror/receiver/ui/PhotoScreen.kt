package dev.imirror.receiver.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import dev.imirror.receiver.airplay.PhotoHandler
import dev.imirror.receiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PhotoScreen — Full-screen ImageView for AirPlay `/photo` sharing.
 *
 * AirPlay accepts photo payloads up to [PhotoHandler.MAX_PHOTO_BYTES] (25 MB), and a 25 MB PNG can
 * decode into hundreds of MB of ARGB pixels. Decoding that on the main thread froze the UI (ANR) or
 * killed the process (OOM). So:
 *  - decoding runs on [Dispatchers.Default] and never on the UI thread;
 *  - the image is downscaled with `inSampleSize` to the view's size (capped at
 *    [MAX_DECODED_DIMENSION] px per side), which bounds the bitmap at ~30 MB even for a huge source;
 *  - the last frame is cached, so repeated overlay updates for the same payload do not decode again.
 */
class PhotoScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private var scope = newScope()
    private var loadJob: Job? = null

    // Cache: the exact payload the visible bitmap was decoded from. Identity comparison is enough —
    // PhotoFrame carries the byte array it arrived with, and overlay updates re-emit the same instance.
    private var shownBytes: ByteArray? = null
    private var shownBitmap: Bitmap? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope = newScope()
    }

    override fun onDetachedFromWindow() {
        // Drop in-flight decodes so a destroyed activity doesn't hold the payload or touch the views.
        loadJob?.cancel()
        loadJob = null
        scope.cancel()
        recycleCached()
        super.onDetachedFromWindow()
    }

    /**
     * Decodes [bytes] off the main thread and shows the result.
     *
     * Returns immediately. [onShown] runs on the main thread once the image is actually on screen —
     * the caller uses it to hide the other overlays, so a failed decode leaves the current screen
     * untouched instead of flashing a black frame.
     */
    fun showPhoto(bytes: ByteArray, onShown: () -> Unit = {}) {
        if (bytes === shownBytes && shownBitmap?.isRecycled == false) {
            onShown()
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            val bounds = probeBounds(bytes)
            if (bounds == null) {
                Logger.w("PhotoScreen: BitmapFactory rejected photo payload (${bytes.size}B)")
                return@launch
            }
            val sampleSize = sampleSizeFor(bounds.first, bounds.second)
            val bitmap = withContext(Dispatchers.Default) { decodeScaled(bytes, sampleSize) }
            if (bitmap == null) {
                Logger.w("PhotoScreen: could not decode photo (${bytes.size}B, sample=$sampleSize)")
                return@launch
            }
            // A newer payload may have replaced this one while we were decoding.
            if (!isActive) return@launch

            recycleCached()
            shownBytes = bytes
            shownBitmap = bitmap
            imageView.setImageBitmap(bitmap)
            onShown()
            Logger.i("PhotoScreen: showing photo ${bounds.first}x${bounds.second} " +
                "→ ${bitmap.width}x${bitmap.height} (${bytes.size / 1024} KB)")
        }
    }

    fun clearPhoto() {
        loadJob?.cancel()
        loadJob = null
        imageView.setImageDrawable(null)
        recycleCached()
    }

    // ─── Decoding helpers ────────────────────────────────────────────────────

    /** Reads only the image header to learn its pixel dimensions (no pixel allocation). */
    private suspend fun probeBounds(bytes: ByteArray): Pair<Int, Int>? =
        withContext(Dispatchers.Default) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) null
            else options.outWidth to options.outHeight
        }

    private fun decodeScaled(bytes: ByteArray, sampleSize: Int): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: OutOfMemoryError) {
        // Downscaling should make this impossible; if an OEM decoder ignores inSampleSize, log and
        // give up rather than taking the process down.
        Logger.e("PhotoScreen: out of memory decoding photo (${bytes.size}B)", e)
        null
    } catch (e: Exception) {
        Logger.e("PhotoScreen: photo decode failed", e)
        null
    }

    /**
     * Largest power-of-two subsample that still covers the target box. A 12000×9000 phone photo on a
     * 1920×1080 TV ends up at ~1500×1125 instead of 10800×8100 (350 MB).
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val targetWidth = targetDimension(Configuration.ORIENTATION_PORTRAIT)
        val targetHeight = targetDimension(Configuration.ORIENTATION_LANDSCAPE)
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    /** The view's long/short side (falling back to the display), clamped to [MAX_DECODED_DIMENSION]. */
    private fun targetDimension(axis: Int): Int {
        val fromView = if (width > 0 && height > 0) {
            if (axis == Configuration.ORIENTATION_PORTRAIT) minOf(width, height) else maxOf(width, height)
        } else {
            val metrics = resources.displayMetrics
            if (axis == Configuration.ORIENTATION_PORTRAIT) minOf(metrics.widthPixels, metrics.heightPixels)
            else maxOf(metrics.widthPixels, metrics.heightPixels)
        }
        return fromView.coerceIn(1, MAX_DECODED_DIMENSION)
    }

    private fun recycleCached() {
        // Detach first: an ImageView still pointing at a recycled Bitmap crashes on the next draw
        // ("trying to use a recycled bitmap"), which is exactly what a detach/attach cycle would do.
        imageView.setImageDrawable(null)
        shownBitmap?.takeIf { !it.isRecycled }?.recycle()
        shownBitmap = null
        shownBytes = null
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        /** Ceiling for either decoded side — keeps the largest cached bitmap around 30 MB. */
        const val MAX_DECODED_DIMENSION = 4096
    }
}
