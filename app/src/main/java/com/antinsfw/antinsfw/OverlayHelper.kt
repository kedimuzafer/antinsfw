package com.antinsfw.antinsfw

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

object OverlayHelper {
    private const val TAG = "OverlayHelper"
    private const val MAX_OVERLAYS = 20

    private const val DEFAULT_OVERLAY_COLOR = Color.BLACK
    private const val DEFAULT_PADDING = 30
    private const val DEFAULT_TOP_EXTRA_PADDING = 15
    private const val DEFAULT_BOTTOM_EXTRA_PADDING = 15
    private const val DEFAULT_SIDE_EXTRA_PADDING = 20
    
    var overlayColor: Int = DEFAULT_OVERLAY_COLOR
        private set
    var padding: Int = DEFAULT_PADDING
        private set
    var topExtraPadding: Int = DEFAULT_TOP_EXTRA_PADDING
        private set
    var bottomExtraPadding: Int = DEFAULT_BOTTOM_EXTRA_PADDING
        private set
    var sideExtraPadding: Int = DEFAULT_SIDE_EXTRA_PADDING
        private set

    private var windowManager: WindowManager? = null
    private var overlayViews: MutableList<OverlayInfo> = mutableListOf()
    private var isServiceStopping = false
    private var screenWidth = 0
    private var screenHeight = 0

    private data class OverlayInfo(
        val view: FrameLayout,
        var box: Rect,
        var layoutParams: WindowManager.LayoutParams
    )

    fun initialize(context: Context) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager!!.currentWindowMetrics
        screenWidth = metrics.bounds.width()
        screenHeight = metrics.bounds.height()
        isServiceStopping = false
        overlayViews.clear()
        Log.d(TAG, "WindowManager başlatıldı")
    }

    fun updateOverlay(context: Context, boxes: List<Rect>, screenWidth: Int, screenHeight: Int) {
        if (isServiceStopping) return

        try {
            Log.d(TAG, "updateOverlay çağrıldı - Yeni kutu sayısı: ${boxes.size}, Mevcut overlay sayısı: ${overlayViews.size}, Ekran: $screenWidth x $screenHeight")

            // Mevcut overlay'lerle yeni kutuları eşleştir
            val currentOverlayCount = overlayViews.size
            val newBoxCount = boxes.size

            // Fazla overlay'leri kaldır
            if (currentOverlayCount > newBoxCount) {
                val overlaysToRemove = overlayViews.takeLast(currentOverlayCount - newBoxCount)
                removeOverlays(overlaysToRemove)
                overlayViews.removeAll(overlaysToRemove)
                Log.d(TAG, "Fazla overlay kaldırıldı: ${overlaysToRemove.size}")
            }

            // Mevcut overlay'leri güncelle
            for (i in 0 until min(currentOverlayCount, newBoxCount)) {
                val overlay = overlayViews[i]
                val newBox = boxes[i]
                val adjustedBox = calculateAdjustedBox(newBox, screenWidth, screenHeight)
                overlay.box = adjustedBox
                updateLayoutParams(overlay.layoutParams, adjustedBox)
                windowManager?.updateViewLayout(overlay.view, overlay.layoutParams)
                Log.v(TAG, "Overlay güncellendi: x=${adjustedBox.left}, y=${adjustedBox.top}, w=${adjustedBox.width()}, h=${adjustedBox.height()}")
            }

            // Yeni overlay'ler oluştur (eksik varsa)
            if (newBoxCount > currentOverlayCount) {
                val newBoxes = boxes.subList(currentOverlayCount, newBoxCount)
                val createdOverlays = createNewOverlays(context, newBoxes, screenWidth, screenHeight)
                overlayViews.addAll(createdOverlays)
                Log.d(TAG, "Yeni overlay oluşturuldu: ${createdOverlays.size}")
            }

            Log.d(TAG, "Overlay güncelleme tamamlandı: Toplam ${overlayViews.size} overlay")
        } catch (e: Exception) {
            Log.e(TAG, "Overlay güncelleme hatası: ${e.message}", e)
        }
    }

    fun clearAllOverlays() {
        try {
            Log.d(TAG, "clearAllOverlays çağrıldı, temizlenecek overlay sayısı: ${overlayViews.size}")
            overlayViews.forEach { overlay ->
                try {
                    windowManager?.removeView(overlay.view)
                    Log.v(TAG, "Overlay kaldırıldı: x=${overlay.box.left}, y=${overlay.box.top}")
                } catch (e: Exception) {
                    Log.e(TAG, "Overlay kaldırma hatası: ${e.message}")
                }
            }
            overlayViews.clear()
            Log.d(TAG, "Tüm overlay’ler temizlendi")
        } catch (e: Exception) {
            Log.e(TAG, "clearAllOverlays hatası: ${e.message}")
        }
    }

    fun hasActiveOverlays(): Boolean = overlayViews.isNotEmpty()

    fun configureOverlayAppearance(
        color: Int = DEFAULT_OVERLAY_COLOR,
        padding: Int = DEFAULT_PADDING,
        topExtra: Int = DEFAULT_TOP_EXTRA_PADDING,
        bottomExtra: Int = DEFAULT_BOTTOM_EXTRA_PADDING,
        sideExtra: Int = DEFAULT_SIDE_EXTRA_PADDING
    ) {
        overlayColor = color
        this.padding = padding
        topExtraPadding = topExtra
        bottomExtraPadding = bottomExtra
        sideExtraPadding = sideExtra
        Log.d(TAG, "Overlay görünümü güncellendi: Renk=#${Integer.toHexString(color)}, Padding=$padding")
    }

    fun getCurrentBoxes(): List<Rect> = overlayViews.map { it.box }

    private fun createNewOverlays(context: Context, boxes: List<Rect>, screenWidth: Int, screenHeight: Int): List<OverlayInfo> {
        val newOverlays = mutableListOf<OverlayInfo>()
        boxes.forEach { box ->
            if (overlayViews.size + newOverlays.size < MAX_OVERLAYS) {
                try {
                    val adjustedBox = calculateAdjustedBox(box, screenWidth, screenHeight)
                    val overlayView = createOverlayView(context)
                    val params = createLayoutParams(adjustedBox)
                    windowManager?.addView(overlayView, params)
                    newOverlays.add(OverlayInfo(overlayView, adjustedBox, params))
                    Log.v(TAG, "Yeni overlay oluşturuldu: x=${adjustedBox.left}, y=${adjustedBox.top}, w=${adjustedBox.width()}, h=${adjustedBox.height()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Yeni overlay oluşturma hatası: ${e.message}")
                }
            }
        }
        return newOverlays
    }

    private fun removeOverlays(overlays: List<OverlayInfo>) {
        overlays.forEach { overlay ->
            try {
                windowManager?.removeView(overlay.view)
                Log.v(TAG, "Overlay kaldırıldı: x=${overlay.box.left}, y=${overlay.box.top}")
            } catch (e: Exception) {
                Log.e(TAG, "Overlay kaldırma hatası: ${e.message}")
            }
        }
    }

    private fun updateLayoutParams(params: WindowManager.LayoutParams, box: Rect) {
        params.x = box.left
        params.y = box.top
        params.width = box.width()
        params.height = box.height()
    }

    private fun createOverlayView(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            setBackgroundResource(R.drawable.rounded_overlay)
            setBackgroundColor(overlayColor)
            alpha = 1.0f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    private fun createLayoutParams(box: Rect): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            box.width(),
            box.height(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = box.left
            y = box.top
        }
    }

    private fun calculateAdjustedBox(original: Rect, screenWidth: Int, screenHeight: Int): Rect {
        val rectWidth = original.width()
        val rectHeight = original.height()

        // Padding oranlarını tanımla (örnek değerler, isteğe göre ayarlanabilir)
        val paddingRatio = 0.20f // %20 genel padding
        val sideExtraRatio = 0.15f // %15 yan ekstra padding
        val topExtraRatio = 0.10f // %10 üst ekstra padding
        val bottomExtraRatio = 0.10f // %10 alt ekstra padding

        // Dinamik padding değerlerini hesapla
        val dynamicPadding = (min(rectWidth, rectHeight) * paddingRatio).toInt()
        val dynamicSideExtra = (rectWidth * sideExtraRatio).toInt()
        val dynamicTopExtra = (rectHeight * topExtraRatio).toInt()
        val dynamicBottomExtra = (rectHeight * bottomExtraRatio).toInt()

        // Ekran sınırlarına göre minimum ve maksimum padding sınırları
        val minPadding = (screenWidth * 0.02f).toInt() // Ekran genişliğinin %2’si
        val maxPadding = (screenWidth * 0.15f).toInt() // Ekran genişliğinin %15’i

        // Padding değerlerini sınırlar içinde tut
        val adjustedPadding = max(minPadding, min(dynamicPadding, maxPadding))
        val adjustedSideExtra = max(minPadding, min(dynamicSideExtra, maxPadding))
        val adjustedTopExtra = max(minPadding, min(dynamicTopExtra, maxPadding))
        val adjustedBottomExtra = max(minPadding, min(dynamicBottomExtra, maxPadding))

        // Yeni Rect’i hesapla
        return Rect(
            max(0, original.left - adjustedPadding - adjustedSideExtra),
            max(0, original.top - adjustedPadding - adjustedTopExtra),
            min(screenWidth, original.right + adjustedPadding + adjustedSideExtra),
            min(screenHeight, original.bottom + adjustedPadding + adjustedBottomExtra)
        )
    }
}