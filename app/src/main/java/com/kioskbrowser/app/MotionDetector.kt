package com.kioskbrowser.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Camera based motion detection: compares a coarse luminance grid of consecutive frames.
 * Also serves camera snapshots for the remote admin ("camshot").
 */
class MotionDetector(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val onMotion: () -> Unit
) {
    private val prefs = Prefs(ctx)
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var prevGrid: IntArray? = null
    private var lastMotion = 0L
    private var frameCount = 0
    @Volatile private var captureRequest: CompletableDeferred<ByteArray>? = null
    @Volatile var running = false
        private set

    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val selector = if (prefs.motionCamera == "back") CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val finalSelector = if (p.hasCamera(selector)) selector
                    else if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                p.unbindAll()
                p.bindToLifecycle(owner, finalSelector, analysis)
                running = true
                Log.i(KioskApp.TAG, "Motion detection started")
            }.onFailure { Log.e(KioskApp.TAG, "Camera start failed", it) }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        running = false
        runCatching { provider?.unbindAll() }
        prevGrid = null
    }

    suspend fun capture(): ByteArray? {
        if (!running) return null
        val d = CompletableDeferred<ByteArray>()
        captureRequest = d
        return withTimeoutOrNull(4000) { d.await() }
    }

    private fun analyze(image: ImageProxy) {
        try {
            captureRequest?.let { req ->
                captureRequest = null
                runCatching { req.complete(toJpeg(image)) }.onFailure { req.completeExceptionally(it) }
            }
            // Analyse ~5 frames per second
            if (frameCount++ % 3 != 0) return
            val grid = lumaGrid(image)
            val prev = prevGrid
            prevGrid = grid
            if (prev == null) return
            var changed = 0
            for (i in grid.indices) if (abs(grid[i] - prev[i]) > PIXEL_DELTA) changed++
            val fraction = changed.toFloat() / grid.size
            val required = 0.30f * (100 - prefs.motionSensitivity) / 100f + 0.01f
            // > 90 % changed is usually a light switch / auto exposure, not motion
            val now = System.currentTimeMillis()
            if (fraction in required..0.9f && now - lastMotion > 1000) {
                lastMotion = now
                onMotion()
            }
        } catch (e: Exception) {
            Log.w(KioskApp.TAG, "analyze failed", e)
        } finally {
            image.close()
        }
    }

    private fun lumaGrid(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val sums = IntArray(GRID_W * GRID_H)
        val counts = IntArray(GRID_W * GRID_H)
        var y = 0
        while (y < h) {
            val gy = y * GRID_H / h
            var x = 0
            while (x < w) {
                val idx = gy * GRID_W + x * GRID_W / w
                sums[idx] += buf.get(y * rowStride + x * pixStride).toInt() and 0xFF
                counts[idx]++
                x += 4
            }
            y += 4
        }
        return IntArray(sums.size) { if (counts[it] > 0) sums[it] / counts[it] else 0 }
    }

    private fun toJpeg(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        var pos = 0
        for (row in 0 until h) {
            for (col in 0 until w) nv21[pos++] = yBuf.get(row * yPlane.rowStride + col * yPlane.pixelStride)
        }
        val u = image.planes[1]
        val v = image.planes[2]
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = v.buffer.get(row * v.rowStride + col * v.pixelStride)
                nv21[pos++] = u.buffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        return ByteArrayOutputStream().use { o -> rotated.compress(Bitmap.CompressFormat.JPEG, 85, o); o.toByteArray() }
    }

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 24
        private const val PIXEL_DELTA = 25
    }
}
