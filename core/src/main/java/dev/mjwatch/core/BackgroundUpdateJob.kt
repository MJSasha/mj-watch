package dev.mjwatch.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress

class BackgroundUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = scope.launch {
            try {
                withContext(Dispatchers.IO) { prepareUpdate(params) }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Background update timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background update failed", e)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private suspend fun prepareUpdate(params: JobParameters) = updateWork.withLock {
        val prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("automatic", true)) return@withLock
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = (if (Build.VERSION.SDK_INT >= 28) params.network else connectivity.activeNetwork)
            ?: return@withLock
        if (connectivity.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true) return@withLock
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory).dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        }).build()
        val source = GitHubUpdates(client)
        val installed = packageManager.getPackageInfo(packageName, 0).appVersionCode()
        val found = source.latest(packageName)?.takeIf { it.versionCode > installed }
        val notifications = getSystemService(NotificationManager::class.java)
        if (found == null) {
            prefs.edit().remove("manifest").apply()
            notifications.cancel(ID)
            return@withLock
        }
        prefs.edit().putString("manifest", found.manifest).apply()
        if (cachedUpdateFile(this, found) == null) downloadUpdate(this, source, found)
        withContext(Dispatchers.Main) {
            if (prefs.getBoolean("automatic", true) &&
                prefs.getLong("notifiedVersion", 0) != found.versionCode &&
                notifyReady(notifications, found)) {
                prefs.edit().putLong("notifiedVersion", found.versionCode).apply()
            }
        }
    }

    private fun notifyReady(manager: NotificationManager, update: AppUpdate): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Обновления MJ Watch", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.setAction(OPEN_UPDATES).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val destination = PendingIntent.getActivity(this, ID, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Обновление MJ Watch готово")
            .setContentText("Версия ${update.versionName} загружена. Нажмите, чтобы установить.")
            .setContentIntent(destination)
            .setAutoCancel(true)
            .build())
        return true
    }

    companion object {
        const val OPEN_UPDATES = "dev.mjwatch.action.OPEN_UPDATES"
        const val CHANNEL = "app-updates"
        private const val ID = 6644
        private const val TAG = "BackgroundUpdate"

        internal fun schedule(context: Context, enabled: Boolean) {
            if (context.packageName != "dev.mjwatch.mobile") return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(ID)
                context.getSystemService(NotificationManager::class.java).cancel(ID)
            } else if (scheduler.getPendingJob(ID) == null) {
                val component = ComponentName(context, BackgroundUpdateJob::class.java)
                val job = JobInfo.Builder(ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .setPeriodic(60 * 60_000L)
                    .setPersisted(true)
                    .build()
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "Could not schedule background updates")
            }
        }
    }
}
