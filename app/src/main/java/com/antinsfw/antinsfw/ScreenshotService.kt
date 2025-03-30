package com.antinsfw.antinsfw

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import kotlin.math.max
import kotlin.math.min

class ScreenshotService : Service() {
    companion object {
        const val TAG = "ScreenshotService"
        const val ACTION_STOP_SERVICE = "com.antinsfw.antinsfw.ACTION_STOP_SERVICE"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 1000L
        private const val DEBOUNCE_TIME_MS = 250L
        var instance: ScreenshotService? = null
    }

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var isRunning = false
    private var consecutiveNonDetections = 0
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var lastAnalysisTime = 0L
    private var lastScreenHash = 0

    private var lastOverlayBoxes = listOf<Rect>()
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var screenChangeDetector: ScreenChangeDetector

    private var overlayMessenger: Messenger? = null
    private var overlayServiceBound = false
    private var reconnectAttempts = 0
    private var lastBindAttemptTime = 0L

    private val screenshotHandler = Handler(Looper.getMainLooper()) {
        when (it.what) {
            OverlayService.MSG_OVERLAY_UPDATED -> {
                val boxes = it.data.getParcelableArrayList<Rect>("adjustedBoxes")
                boxes?.let { b ->
                    lastOverlayBoxes = b
                    Log.d(TAG, "OverlayService’ten güncel kutular alındı: ${b.size}")
                } ?: Log.w(TAG, "OverlayService’ten boş kutu listesi alındı")
                true
            }
            else -> false
        }
    }

    private val screenshotMessenger = Messenger(screenshotHandler)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                overlayMessenger = Messenger(service)
                overlayServiceBound = true
                reconnectAttempts = 0
                Log.d(TAG, "OverlayService bağlantısı başarılı")
            } catch (e: Exception) {
                Log.e(TAG, "OverlayService bağlantı hatası: ${e.message}")
                handleBindError()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            overlayMessenger = null
            overlayServiceBound = false
            Log.d(TAG, "OverlayService bağlantısı koptu")
            handleBindError()
        }
    }

    private fun handleBindError() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val now = System.currentTimeMillis()
            if (now - lastBindAttemptTime > RECONNECT_DELAY_MS) {
                lastBindAttemptTime = now
                scope.launch {
                    delay(RECONNECT_DELAY_MS)
                    Log.d(TAG, "OverlayService yeniden bağlanma denemesi: $reconnectAttempts")
                    bindToOverlayService()
                }
            }
        } else {
            Log.e(TAG, "OverlayService bağlantısı başarısız, maksimum deneme sayısına ulaşıldı")
        }
    }

    private fun bindToOverlayService() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "OverlayService bağlantı denemesi başlatıldı")
        } catch (e: Exception) {
            Log.e(TAG, "OverlayService bağlantı hatası: ${e.message}")
            handleBindError()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        screenCaptureManager = ScreenCaptureManager(this, screenWidth, screenHeight, screenDensity)
        screenChangeDetector = ScreenChangeDetector()

        NudeDetector.initialize(this)
        GenderDetector.initialize(this)
        Log.i(TAG, "Servis oluşturuldu: width=$screenWidth, height=$screenHeight")
        
        broadcastServiceState(MainActivity.ACTION_SERVICE_STARTED)
        bindToOverlayService()
        
        startAccessibilityService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "Stop service action received")
            stopSelf()
        } else if (intent?.action == "START") {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            
            startForegroundServiceWithNotification()
            try {
                screenCaptureManager.startProjection(resultCode, data)
                startScreenshotCapture()
                Log.i(TAG, "Servis başlatıldı, ekran yakalama aktif")
            } catch (e: Exception) {
                Log.e(TAG, "Ekran yakalama başlatılamadı: ${e.message}", e)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "screenshot_service_channel"
        val channel = NotificationChannel(channelId, "Screenshot Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ekran Görüntüsü Servisi")
            .setContentText("Ekran görüntüleri alınıyor...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", createStopServicePendingIntent())
            .build()

        startForeground(1, notification)
        Log.d(TAG, "Ön plan servisi başlatıldı")
    }

    private fun createStopServicePendingIntent(): PendingIntent {
        val stopIntent = Intent(this, StopScreenshotServiceReceiver::class.java)
        stopIntent.action = StopScreenshotServiceReceiver.ACTION_STOP_SERVICE
        return PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun startScreenshotCapture() {
        isRunning = true
        scope.launch {
            while (isActive && isRunning) {
                checkScreenChangeAndAnalyze()
            }
        }
        Log.d(TAG, "Ekran yakalama döngüsü başlatıldı")
    }

    private fun broadcastServiceState(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: $action")
    }

    private suspend fun checkScreenChangeAndAnalyze() {
        withContext(Dispatchers.IO) {
            try {
                if (System.currentTimeMillis() - lastAnalysisTime < DEBOUNCE_TIME_MS) return@withContext

                val isScrolling = ScrollDetectionService.isScrolling()
                if (isScrolling) {
                    Log.d(TAG, "Scroll devam ediyor, ekran görüntüsü alınmadı")
                    return@withContext
                }

                val image = screenCaptureManager.acquireLatestImage() ?: return@withContext
                try {
                    val bitmap = screenCaptureManager.createBitmapFromImage(image)
                    val previousOverlayBoxes = lastOverlayBoxes

                    if (screenChangeDetector.isSignificantChange(bitmap, lastOverlayBoxes, previousOverlayBoxes)) {
                        analyzeAndDrawOverlay(bitmap)
                        lastAnalysisTime = System.currentTimeMillis()
                    } else {
                        Log.d(TAG, "Ekran değişmedi, mevcut overlay'ler korunuyor: ${lastOverlayBoxes.size}")
                    }
                    bitmap.recycle()
                } finally {
                    image.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hata: ${e.message}")
            }
        }
        delay(100)
    }

    private suspend fun analyzeAndDrawOverlay(bitmap: Bitmap) {
        val detections = NudeDetector.detect(bitmap, this)

        if (detections.isNotEmpty()) {
            consecutiveNonDetections = 0
            val scaleX = screenWidth.toFloat() / bitmap.width
            val scaleY = screenHeight.toFloat() / bitmap.height

            val scaledBoxes = detections.map { detection ->
                val box = detection["box"] as List<Int>
                Rect(
                    (box[0] * scaleX).toInt().coerceAtLeast(0),
                    (box[1] * scaleY).toInt().coerceAtLeast(0),
                    ((box[0] + box[2]) * scaleX).toInt().coerceAtMost(screenWidth),
                    ((box[1] + box[3]) * scaleY).toInt().coerceAtMost(screenHeight)
                )
            }
            sendOverlayUpdate(scaledBoxes)
        } else {
            consecutiveNonDetections++
            if (consecutiveNonDetections >= 3) {
                Log.d(TAG, "Üst üste 3 kez tespit yok, overlay'ler temizleniyor")
                sendOverlayUpdate(emptyList())
                consecutiveNonDetections = 0
            }
        }
    }

    private fun sendOverlayUpdate(boxes: List<Rect>) {
        if (!overlayServiceBound || overlayMessenger == null) {
            Log.w(TAG, "OverlayService bağlı değil, güncelleme gönderilemiyor")
            handleBindError()
            return
        }

        val paddedBoxes = boxes.map { box ->
            Rect(
                box.left - OverlayHelper.padding - OverlayHelper.sideExtraPadding,
                box.top - OverlayHelper.padding - OverlayHelper.topExtraPadding,
                box.right + OverlayHelper.padding + OverlayHelper.sideExtraPadding,
                box.bottom + OverlayHelper.padding + OverlayHelper.bottomExtraPadding
            ).apply {
                left = max(0, left)
                top = max(0, top)
                right = min(screenWidth, right)
                bottom = min(screenHeight, bottom)
            }
        }

        Log.d(TAG, "Scaled and padded overlay boxes: ${paddedBoxes.joinToString { "[${it.left},${it.top},${it.right},${it.bottom}]" }}")

        try {
            val pid = Process.myPid()
            val message = if (paddedBoxes.isEmpty()) {
                Message.obtain(null, OverlayService.MSG_CLEAR_OVERLAYS)
            } else {
                Message.obtain(null, OverlayService.MSG_UPDATE_OVERLAYS).apply {
                    data = Bundle().apply {
                        putParcelableArrayList("boxes", ArrayList(paddedBoxes))
                        putInt("screen_width", screenWidth)
                        putInt("screen_height", screenHeight)
                        putInt("source_process", pid)
                    }
                }
            }
            message.replyTo = screenshotMessenger
            overlayMessenger?.send(message)
            Log.d(TAG, if (paddedBoxes.isEmpty()) "Overlay temizleme gönderildi" else "Overlay güncelleme gönderildi: ${paddedBoxes.size} kutu")
        } catch (e: RemoteException) {
            Log.e(TAG, "Overlay güncelleme gönderme hatası: ${e.message}")
            overlayServiceBound = false
            handleBindError()
        }
    }

    private fun startAccessibilityService() {
        try {
            val intent = Intent(this, ScrollDetectionService::class.java)
            startService(intent)
            Log.d(TAG, "ScrollDetectionService başlatıldı")
        } catch (e: Exception) {
            Log.e(TAG, "ScrollDetectionService başlatılamadı: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job.cancel()
        if (overlayServiceBound) {
            unbindService(serviceConnection)
            overlayServiceBound = false
        }
        try {
            screenCaptureManager.stopProjection()
            screenChangeDetector.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Projection durdurma hatası", e)
        }
        instance = null
        broadcastServiceState(MainActivity.ACTION_SERVICE_STOPPED)
        Log.i(TAG, "Servis durduruldu")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}