package com.antinsfw.antinsfw

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

class ScreenCaptureManager(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var actualScaleFactor = 1.0f

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection durduruldu")
            stopProjection()
        }
    }

    fun startProjection(resultCode: Int, data: Intent?) {
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (data == null) {
                Log.e(TAG, "Projection data is null, stopping")
                return
            }
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, null)

            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888,
                4
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )
            Log.d(TAG, "Projection başlatıldı: ${screenWidth}x${screenHeight}, density: $screenDensity")
        } catch (e: Exception) {
            Log.e(TAG, "Projection başlatma hatası: ${e.message}", e)
            throw e
        }
    }

    fun acquireLatestImage(): Image? {
        return try {
            imageReader?.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to acquire image: ${e.message}", e)
            null
        }
    }

    fun createBitmapFromImage(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = BitmapPool.getBitmap(screenWidth + rowPadding / pixelStride, screenHeight)
        bitmap.copyPixelsFromBuffer(buffer)
        val newBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        BitmapPool.returnBitmap(bitmap)

        actualScaleFactor = newBitmap.width.toFloat() / screenWidth
        return newBitmap
    }

    fun getScaleFactor(): Float = actualScaleFactor

    fun stopProjection() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            Log.d(TAG, "Projection durduruldu")
        } catch (e: Exception) {
            Log.e(TAG, "Projection durdurma hatası: ${e.message}", e)
        }
    }

    fun release() {
        stopProjection()
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}