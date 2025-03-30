package com.antinsfw.antinsfw

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.min

class ScreenChangeDetector {
    private var lastBitmap: Bitmap? = null
    private var lastOverlayBoxes: List<Rect> = emptyList()
    private val blockSize = 32 // Increased from 16 to 32 for better performance
    private val pixelStep = 4  // Increased from 2 to 4 for better performance

    companion object {
        private const val CHANGE_THRESHOLD = 0.05 // Increased from 0.03 to 0.05 (5%)
    }

    fun isSignificantChange(currentBitmap: Bitmap, currentOverlayBoxes: List<Rect>, previousOverlayBoxes: List<Rect>): Boolean {
        val last = lastBitmap ?: run {
            lastBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, false)
            lastOverlayBoxes = currentOverlayBoxes
            Log.d("ScreenChangeDetector", "First bitmap saved")
            return true
        }

        if (last.width != currentBitmap.width || last.height != currentBitmap.height) {
            lastBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, false)
            lastOverlayBoxes = currentOverlayBoxes
            return true
        }

        // Combine current and previous overlay boxes
        val allOverlays = (currentOverlayBoxes + previousOverlayBoxes).distinct()
        var totalChangedPixels = 0
        var totalPixelsChecked = 0

        // Block-based sampling with optimized overlay skipping
        for (yBlock in 0 until currentBitmap.height step blockSize) {
            for (xBlock in 0 until currentBitmap.width step blockSize) {
                // Check if the block is completely inside any overlay
                val isBlockInOverlay = allOverlays.any { overlay ->
                    xBlock >= overlay.left && xBlock + blockSize <= overlay.right &&
                    yBlock >= overlay.top && yBlock + blockSize <= overlay.bottom
                }
                
                if (isBlockInOverlay) continue // Skip entire block if it's in overlay

                // Sample pixels within block
                for (y in yBlock until min(yBlock + blockSize, currentBitmap.height) step pixelStep) {
                    for (x in xBlock until min(xBlock + blockSize, currentBitmap.width) step pixelStep) {
                        val currentPixel = currentBitmap.getPixel(x, y)
                        val lastPixel = last.getPixel(x, y)
                        if (isDifferent(currentPixel, lastPixel)) {
                            totalChangedPixels++
                        }
                        totalPixelsChecked++
                    }
                }
            }
        }

        val changePercentage = totalChangedPixels.toDouble() / totalPixelsChecked
        Log.d("ScreenChangeDetector", "Change: ${"%.2f".format(changePercentage * 100)}% (${totalChangedPixels}/${totalPixelsChecked})")

        lastBitmap?.recycle()
        lastBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, false)
        lastOverlayBoxes = currentOverlayBoxes

        return changePercentage > CHANGE_THRESHOLD
    }

    private fun isDifferent(color1: Int, color2: Int): Boolean {
        val threshold = 70 // Increased from 50 to 70 for better accuracy
        val gray1 = (Color.red(color1) * 0.299 + Color.green(color1) * 0.587 + Color.blue(color1) * 0.114).toInt()
        val gray2 = (Color.red(color2) * 0.299 + Color.green(color2) * 0.587 + Color.blue(color2) * 0.114).toInt()
        return abs(gray1 - gray2) > threshold
    }

    fun clear() {
        lastBitmap?.recycle()
        lastBitmap = null
        lastOverlayBoxes = emptyList()
    }
}