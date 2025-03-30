package com.antinsfw.antinsfw

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {
    companion object {
        const val TAG = "OverlayService"
        const val MSG_UPDATE_OVERLAYS = 1
        const val MSG_CLEAR_OVERLAYS = 2
        const val MSG_STOP_SERVICE = 3
        const val MSG_OVERLAY_UPDATED = 4
        var instance: OverlayService? = null
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var isServiceStopping = false
    private var pendingBoxes: List<Rect>? = null
    private var lastUpdateTime = 0L
    private val updateHandler = Handler(Looper.getMainLooper())
    private val BATCH_DELAY_MS = 100L

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (isServiceStopping) {
                Log.d(TAG, "Servis durduruluyor, mesaj işleme atlandı")
                return
            }

            val data = msg.data
            val sourceProcess = data.getInt("source_process", -1)
            val processName = getProcessName(sourceProcess)
            val replyTo = msg.replyTo

            Log.d(TAG, "Mesaj alındı: ${msg.what}, PID: $sourceProcess, Process: $processName")

            when (msg.what) {
                MSG_UPDATE_OVERLAYS -> {
                    val boxes = data.getParcelableArrayList<Rect>("boxes")
                    val screenWidth = data.getInt("screen_width", 0)
                    val screenHeight = data.getInt("screen_height", 0)

                    if (boxes != null && screenWidth > 0 && screenHeight > 0) {
                        pendingBoxes = boxes
                        updateHandler.removeCallbacksAndMessages(null)
                        updateHandler.postDelayed({
                            pendingBoxes?.let {
                                updateOverlay(this@OverlayService, it, screenWidth, screenHeight, replyTo)
                                pendingBoxes = null
                            }
                        }, BATCH_DELAY_MS)
                    } else {
                        Log.e(TAG, "Geçersiz overlay güncelleme mesajı: kutular null veya ekran boyutları geçersiz")
                    }
                }
                MSG_CLEAR_OVERLAYS -> {
                    Log.d(TAG, "Overlay temizleme isteği alındı")
                    clearOverlays()
                }
                MSG_STOP_SERVICE -> {
                    Log.d(TAG, "Servis durdurma isteği alındı")
                    stopSelf()
                }
                else -> Log.w(TAG, "Bilinmeyen mesaj: ${msg.what}")
            }
        }
    }

    private fun updateOverlay(context: Context, boxes: List<Rect>, screenWidth: Int, screenHeight: Int, replyTo: Messenger?) {
        Log.d(TAG, "Overlay güncellendi: ${boxes.size} kutu, Ekran: $screenWidth x $screenHeight")
        
        scope.launch {
            withContext(Dispatchers.Main) {
                OverlayHelper.updateOverlay(context, boxes, screenWidth, screenHeight)
                val adjustedBoxes = OverlayHelper.getCurrentBoxes()
                val replyMsg = Message.obtain(null, MSG_OVERLAY_UPDATED).apply {
                    data = Bundle().apply {
                        putParcelableArrayList("adjustedBoxes", ArrayList(adjustedBoxes))
                    }
                }
                try {
                    replyTo?.send(replyMsg)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Yanıt gönderilemedi: ${e.message}")
                }
            }
        }
    }

    private val messenger = Messenger(IncomingHandler(Looper.getMainLooper()))

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "OverlayService onCreate başladı")
        
        try {
            OverlayHelper.initialize(this)
            startForegroundService()
            Log.i(TAG, "Overlay servisi başarıyla başlatıldı")
        } catch (e: Exception) {
            Log.e(TAG, "OverlayService başlatılırken hata oluştu", e)
        }
    }

    private fun startForegroundService() {
        try {
            val channelId = "overlay_service_channel"
            val channel = NotificationChannel(
                channelId,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Sansür Servisi")
                .setContentText("Sansürleme aktif...")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .build()

            startForeground(2, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Foreground servis başlatılırken hata", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceStopping = true
        instance = null
        OverlayHelper.clearAllOverlays()
        updateHandler.removeCallbacksAndMessages(null)
        job.cancel()
        Log.i(TAG, "Overlay servisi durduruldu")
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    private fun getProcessName(pid: Int): String {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (processInfo in manager.runningAppProcesses ?: emptyList()) {
            if (processInfo.pid == pid) {
                return processInfo.processName ?: ""
            }
        }
        return ""
    }

    private fun clearOverlays() {
        Log.d(TAG, "MSG_CLEAR_OVERLAYS işleniyor")
        scope.launch {
            withContext(Dispatchers.Main) {
                OverlayHelper.clearAllOverlays()
            }
        }
    }
}