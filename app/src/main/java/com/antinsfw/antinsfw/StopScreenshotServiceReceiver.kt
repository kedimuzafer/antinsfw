package com.antinsfw.antinsfw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class StopScreenshotServiceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_SERVICE = "com.antinsfw.antinsfw.ACTION_STOP_SERVICE"
        private const val TAG = "StopServiceReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received stop service broadcast")
        
        // First try to stop with ACTION_STOP_SERVICE
        val serviceIntent = Intent(context, ScreenshotService::class.java)
        serviceIntent.action = ScreenshotService.ACTION_STOP_SERVICE
        
        // For Android O and above, we need to use startForegroundService for stopping a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "Sent stop command via startForegroundService")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending stop via startForegroundService: ${e.message}")
                // Fallback to direct stopService
                context.stopService(serviceIntent)
            }
        } else {
            context.startService(serviceIntent)
            Log.d(TAG, "Sent stop command via startService")
        }
        
        // Also try direct stopService as a fallback
        try {
            val directStopIntent = Intent(context, ScreenshotService::class.java)
            context.stopService(directStopIntent)
            Log.d(TAG, "Directly called stopService as fallback")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service directly: ${e.message}")
        }
        
        // Also stop OverlayService
        try {
            val overlayIntent = Intent(context, OverlayService::class.java)
            context.stopService(overlayIntent)
            Log.d(TAG, "Stopped OverlayService")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping OverlayService: ${e.message}")
        }
    }
}