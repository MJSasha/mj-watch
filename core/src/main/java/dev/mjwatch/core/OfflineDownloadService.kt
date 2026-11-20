package dev.mjwatch.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

/** Foreground transfer for Android versions before the user-initiated job API. */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { OfflineDownloads.get(this) }
    private val notification by lazy { OfflineDownloadNotification(this) }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock?.isHeld != true) {
            startForeground(OfflineDownloadNotification.ID, notification.build())
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MJWatch:offline-download")
                .apply { acquire(6L * 60 * 60 * 1000) }
        }
        scope.launch {
            try {
                store.drain(notify = notification::publish)
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
