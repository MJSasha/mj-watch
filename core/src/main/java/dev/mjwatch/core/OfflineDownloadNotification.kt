package dev.mjwatch.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.text.format.Formatter

/** The same notification follows the queue on both Android scheduling paths. */
internal class OfflineDownloadNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val destination = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
        val intent = launch.setAction(OfflineDownloads.OPEN_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private var itemId: String? = null
    private var previousBytes = 0L
    private var previousTime = 0L
    private var rate = 0.0

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки видео", NotificationManager.IMPORTANCE_LOW))
    }

    fun publish(item: OfflineDownload) {
        manager.notify(ID, build(item))
    }

    @Synchronized
    fun build(item: OfflineDownload? = null): Notification {
        val total = item?.totalBytes ?: 0
        val bytes = item?.bytesDownloaded?.coerceIn(0, total) ?: 0
        val percent = if (total > 0) (bytes.toDouble() / total * 100).toInt().coerceAtMost(99) else 0
        val speed = if (item != null) measureSpeed(item, bytes) else null
        val eta = speed?.let { (total - bytes) / it }?.takeIf { it in 5L..3600L }?.let {
            if (it < 60) "≈ ${(it + 4) / 5 * 5} с" else "≈ ${(it + 30) / 60} мин"
        }
        val details = if (total > 0) {
            listOfNotNull("$percent%", "${format(bytes)} из ${format(total)}",
                item?.error ?: speed?.let { "${format(it)}/с" }, eta).joinToString(" · ")
        } else "Подготовка загрузки"
        val title = item?.let { download -> listOfNotNull(download.title,
            download.episode?.let { "Серия $it" }).joinToString(" · ") } ?: "Загрузка видео"
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(details)
            .setSubText("Загрузки")
            .setContentIntent(destination)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 36) {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val accent = if (night) 0xff31f5c8.toInt() else 0xff00674f.toInt()
            builder.setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(accent)))
                .setProgress(percent)
                .setProgressIndeterminate(total == 0L)
                .setProgressTrackerIcon(Icon.createWithResource("android", android.R.drawable.ic_media_play)))
        } else builder.setProgress(100, percent, total == 0L)
        return builder.build()
    }

    private fun format(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    private fun measureSpeed(item: OfflineDownload, bytes: Long): Long? {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - previousTime
        val delta = bytes - previousBytes
        if (itemId != item.id || item.error != null || elapsed > 5_000 || delta < 0) {
            rate = 0.0
        } else if (elapsed >= 500) {
            val sample = delta * 1000.0 / elapsed
            rate = if (rate > 0) rate * .7 + sample * .3 else sample
        } else return rate.toLong().takeIf { it > 0 }
        itemId = item.id
        previousBytes = bytes
        previousTime = now
        return rate.toLong().takeIf { it > 0 }
    }

    companion object {
        private const val CHANNEL = "offline-downloads"
        const val ID = 6642
    }
}
