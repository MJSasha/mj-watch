package dev.mjwatch.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { OfflineDownloads.get(this) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки видео", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(null))
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MJWatch:offline-download").apply {
                    acquire(WAKE_TIMEOUT_MS)
                }
        }
        scope.launch {
            try {
                store.drain { manager.notify(NOTIFICATION, notification(it)) }
            } finally { stopSelfResult(startId) }
        }
        return START_STICKY
    }

    private fun notification(item: OfflineDownload?): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка видео")
            .setContentText(item?.title ?: "Подготовка загрузки")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (item != null) {
            val percent = (item.bytesDownloaded.toDouble() / item.totalBytes * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else builder.setProgress(0, 0, true)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { store.interruptAll("Android остановил фоновую загрузку. Откройте приложение и продолжите") }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "offline-downloads"
        const val NOTIFICATION = 6642
        const val WAKE_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
