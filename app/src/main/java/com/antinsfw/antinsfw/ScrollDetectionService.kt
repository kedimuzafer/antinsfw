package com.antinsfw.antinsfw

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ScrollDetectionService : AccessibilityService() {
    companion object {
        private const val TAG = "ScrollDetectionService"
        private var instance: ScrollDetectionService? = null
        private var isScrolling = false

        fun isScrolling(): Boolean = isScrolling
    }

    private val handler = Handler(Looper.getMainLooper()) // Handler tanımlandı

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "ScrollDetectionService bağlandı")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            isScrolling = true
            Log.d(TAG, "Scroll algılandı: ${event.source?.className}")

            // Scroll bittikten sonra durumu sıfırlamak için bir gecikme
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                isScrolling = false
                Log.d(TAG, "Scroll bitti")
            }, 300) // 300ms gecikme, scroll bitti varsayımı
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "ScrollDetectionService kesintiye uğradı")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null) // Handler temizliği
        instance = null
        isScrolling = false
        Log.d(TAG, "ScrollDetectionService durduruldu")
    }
}