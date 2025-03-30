package com.antinsfw.antinsfw

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val PREF_NAME = "AntiNSFWPrefs"
        private const val TAG = "MainActivity"
        private const val STATS_UPDATE_INTERVAL = 1000L
        const val ACTION_SERVICE_STARTED = "com.antinsfw.antinsfw.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.antinsfw.antinsfw.SERVICE_STOPPED"
    }

    private val PERMISSION_REQUEST_CODE = 123
    private var mediaProjectionManager: MediaProjectionManager? = null
    private lateinit var sharedPreferences: SharedPreferences
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsUpdateRunnable: Runnable? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SERVICE_STARTED -> {
                    Log.d(TAG, "Received service started broadcast")
                    updateServiceStatus()
                    startStatsUpdates()
                }
                ACTION_SERVICE_STOPPED -> {
                    Log.d(TAG, "Received service stopped broadcast")
                    updateServiceStatus()
                    stopStatsUpdates()
                }
            }
        }
    }

    private lateinit var permissionStatusTitle: TextView
    private lateinit var permissionsContainer: LinearLayout
    private lateinit var notificationPermissionLayout: LinearLayout
    private lateinit var foregroundServicePermissionLayout: LinearLayout
    private lateinit var overlayPermissionLayout: LinearLayout
    private lateinit var notificationPermissionStatus: TextView
    private lateinit var foregroundServicePermissionStatus: TextView
    private lateinit var overlayPermissionStatus: TextView
    private lateinit var notificationPermissionButton: Button
    private lateinit var foregroundServicePermissionButton: Button
    private lateinit var overlayPermissionButton: Button
    private lateinit var actionButton: Button
    
    private lateinit var performanceStatsContainer: LinearLayout
    private lateinit var sampleCountText: TextView
    private lateinit var preprocessTimeText: TextView
    private lateinit var inferenceTimeText: TextView
    private lateinit var postprocessTimeText: TextView
    private lateinit var totalTimeText: TextView

    private val startScreenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startScreenshotService(result.resultCode, result.data)
            } else {
                Toast.makeText(this, "Ekran görüntüsü izni reddedildi", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updatePermissionStatuses()
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "İzin reddedildi. Ayarlardan manuel olarak izin vermeniz gerekiyor.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize both detectors in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NudeDetector.initialize(applicationContext)
                GenderDetector.initialize(applicationContext)
                Log.d(TAG, "Both detectors initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Detector initialization failed: ${e.message}")
            }
        }

        initializeViews()
        updatePermissionStatuses()
        updateServiceStatus()

        val filter = IntentFilter().apply {
            addAction(ACTION_SERVICE_STARTED)
            addAction(ACTION_SERVICE_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceStateReceiver, filter)
        
        Log.d(TAG, "MainActivity started")
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        updateServiceStatus()
        if (isScreenshotServiceRunning()) {
            startStatsUpdates()
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopStatsUpdates()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopStatsUpdates()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStateReceiver)
        
        // Cleanup both detectors
        try {
            GenderDetector.release()
            NudeDetector.release()
            Log.d(TAG, "Detectors released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing detectors: ${e.message}")
        }
        
        Log.d(TAG, "Activity destroyed")
    }

    private fun initializeViews() {
        permissionStatusTitle = findViewById(R.id.permissionStatusTitle)
        permissionsContainer = findViewById(R.id.permissionsContainer)
        notificationPermissionLayout = findViewById(R.id.notificationPermissionLayout)
        foregroundServicePermissionLayout = findViewById(R.id.foregroundServicePermissionLayout)
        overlayPermissionLayout = findViewById(R.id.overlayPermissionLayout)
        notificationPermissionStatus = findViewById(R.id.notificationPermissionStatus)
        foregroundServicePermissionStatus = findViewById(R.id.foregroundServicePermissionStatus)
        overlayPermissionStatus = findViewById(R.id.overlayPermissionStatus)
        notificationPermissionButton = findViewById(R.id.notificationPermissionButton)
        foregroundServicePermissionButton = findViewById(R.id.foregroundServicePermissionButton)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton)
        actionButton = findViewById(R.id.actionButton)
        
        performanceStatsContainer = findViewById(R.id.performanceStatsContainer)
        sampleCountText = findViewById(R.id.sampleCountText)
        preprocessTimeText = findViewById(R.id.preprocessTimeText)
        inferenceTimeText = findViewById(R.id.inferenceTimeText)
        postprocessTimeText = findViewById(R.id.postprocessTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        notificationPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppSettings()
                }
            }
        }

        foregroundServicePermissionButton.setOnClickListener {
            openAppSettings()
        }

        overlayPermissionButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        actionButton.setOnClickListener {
            if (isScreenshotServiceRunning() || isOverlayServiceRunning()) {
                stopServices()
            } else {
                startCapture()
            }
        }
    }

    private fun updatePermissionStatuses() {
        var allPermissionsGranted = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isNotificationGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
            val notificationStatus = if (isNotificationGranted) "Verildi ✓" else "Verilmedi ✗"
            notificationPermissionStatus.text = "Bildirim İzni: $notificationStatus"
            notificationPermissionButton.isEnabled = !isNotificationGranted
            
            if (!isNotificationGranted) allPermissionsGranted = false
        } else {
            notificationPermissionLayout.visibility = View.GONE
            notificationPermissionStatus.text = "Bildirim İzni: Gerekli Değil ✓"
            notificationPermissionButton.isEnabled = false
        }

        foregroundServicePermissionStatus.text = "Ön Plan Servis İzni: Verildi ✓"
        foregroundServicePermissionButton.isEnabled = false

        val isOverlayGranted = Settings.canDrawOverlays(this)
        val overlayStatus = if (isOverlayGranted) "Verildi ✓" else "Verilmedi ✗"
        overlayPermissionStatus.text = "Overlay İzni: $overlayStatus"
        overlayPermissionButton.isEnabled = !isOverlayGranted
        if (!isOverlayGranted) allPermissionsGranted = false

        permissionsContainer.visibility = if (allPermissionsGranted) View.GONE else View.VISIBLE
        permissionStatusTitle.visibility = if (allPermissionsGranted) View.GONE else View.VISIBLE
        
        actionButton.isEnabled = allPermissionsGranted
    }

    private fun updateServiceStatus() {
        val screenshotServiceRunning = isScreenshotServiceRunning()
        val overlayServiceRunning = isOverlayServiceRunning()
        
        if (screenshotServiceRunning || overlayServiceRunning) {
            actionButton.text = "Ekran Görüntüsü Almayı Durdur"
            performanceStatsContainer.visibility = View.VISIBLE
        } else {
            actionButton.text = "Ekran Görüntüsü Almayı Başlat"
            performanceStatsContainer.visibility = View.GONE
            PerformanceStats.reset()
        }
    }
    
    private fun startStatsUpdates() {
        if (statsUpdateRunnable != null) return
        
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                updatePerformanceStats()
                statsHandler.postDelayed(this, STATS_UPDATE_INTERVAL)
            }
        }
        statsHandler.post(statsUpdateRunnable!!)
    }
    
    private fun stopStatsUpdates() {
        statsUpdateRunnable?.let {
            statsHandler.removeCallbacks(it)
            statsUpdateRunnable = null
        }
    }
    
    private fun updatePerformanceStats() {
        if (!isScreenshotServiceRunning()) return
        
        val sampleCount = PerformanceStats.getSampleCount()
        val avgPreprocess = PerformanceStats.getAveragePreprocessTime()
        val avgInference = PerformanceStats.getAverageInferenceTime()
        val avgPostprocess = PerformanceStats.getAveragePostprocessTime()
        val avgTotal = PerformanceStats.getAverageTotalTime()
        
        sampleCountText.text = "Örnek Sayısı: $sampleCount/20"
        preprocessTimeText.text = "Ön İşleme: ${avgPreprocess}ms"
        inferenceTimeText.text = "Inference: ${avgInference}ms"
        postprocessTimeText.text = "Son İşleme: ${avgPostprocess}ms"
        totalTimeText.text = "Toplam İşleme: ${avgTotal}ms"
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun startCapture() {
        mediaProjectionManager?.let {
            startScreenCapture.launch(it.createScreenCaptureIntent())
        } ?: run {
            Toast.makeText(this, "MediaProjectionManager başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScreenshotService(resultCode: Int, data: Intent?) {
        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }
        
        val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
            action = "START"
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Servisler başlatıldı", Toast.LENGTH_SHORT).show()
        
        actionButton.text = "Ekran Görüntüsü Almayı Durdur"
        performanceStatsContainer.visibility = View.VISIBLE
        
        val startedIntent = Intent(ACTION_SERVICE_STARTED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(startedIntent)
    }
    
    private fun stopServices() {
        if (isScreenshotServiceRunning()) {
            val stopIntent = Intent(this, StopScreenshotServiceReceiver::class.java)
            stopIntent.action = StopScreenshotServiceReceiver.ACTION_STOP_SERVICE
            sendBroadcast(stopIntent)
        }
        
        if (isOverlayServiceRunning()) {
            val overlayIntent = Intent(this, OverlayService::class.java)
            stopService(overlayIntent)
        }
        
        actionButton.text = "Ekran Görüntüsü Almayı Başlat"
        performanceStatsContainer.visibility = View.GONE
        
        val stoppedIntent = Intent(ACTION_SERVICE_STOPPED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(stoppedIntent)
        
        Toast.makeText(this, "Servisler durduruldu", Toast.LENGTH_SHORT).show()
    }
    
    private fun isScreenshotServiceRunning(): Boolean {
        return ScreenshotService.instance != null
    }
    
    private fun isOverlayServiceRunning(): Boolean {
        return OverlayService.instance != null
    }
}