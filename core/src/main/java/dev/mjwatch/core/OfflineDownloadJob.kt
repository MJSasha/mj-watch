package dev.mjwatch.core

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

class OfflineDownloadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    @RequiresApi(34)
    override fun onStartJob(params: JobParameters): Boolean {
        val notification = OfflineDownloadNotification(this)
        setNotification(params, OfflineDownloadNotification.ID, notification.build(),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        worker = scope.launch {
            OfflineDownloads.get(this@OfflineDownloadJob).drain(onJobFinished = {
                withContext(Dispatchers.Main.immediate) { jobFinished(params, false) }
            }) { item -> notification.publish(item) }
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

    internal companion object { const val ID = 6643 }
}
