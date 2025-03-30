package com.antinsfw.antinsfw

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

object BitmapPool {
    private const val TAG = "BitmapPool"
    private const val MAX_POOL_SIZE = 5
    private val pool = ConcurrentLinkedQueue<Bitmap>()

    fun getBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        synchronized(pool) {
            val bitmap = pool.find { it.width == width && it.height == height && !it.isRecycled }
            if (bitmap != null) {
                pool.remove(bitmap)
                bitmap.eraseColor(0)
                Log.d(TAG, "Reused bitmap from pool: ${width}x${height}")
                return bitmap
            }
        }
        Log.d(TAG, "Created new bitmap: ${width}x${height}")
        return Bitmap.createBitmap(width, height, config)
    }

    fun returnBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Attempted to return recycled bitmap")
            return
        }
        synchronized(pool) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(bitmap)
                Log.d(TAG, "Bitmap returned to pool, size: ${pool.size}")
            } else {
                bitmap.recycle()
                Log.d(TAG, "Pool full, bitmap recycled")
            }
        }
    }

    fun clear() {
        synchronized(pool) {
            while (pool.isNotEmpty()) {
                pool.poll()?.recycle()
            }
            Log.d(TAG, "Bitmap pool cleared")
        }
    }
}