package com.powerhub.pulse

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WidgetRefreshWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.init(applicationContext)
        if(Store.state.value.running) {PulseWidget.refresh(applicationContext);return@withContext Result.success()}
        try {
            val s=Store.settings(applicationContext)
            Sync.pull(applicationContext,s)
            Result.success()
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            PulseWidget.refresh(applicationContext)
            Result.retry()
        }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifier.channels(applicationContext)
        val notification=androidx.core.app.NotificationCompat.Builder(applicationContext,Notifier.MONITOR)
            .setSmallIcon(R.drawable.ic_pulse).setContentTitle("Deye Pulse")
            .setContentText("Оновлення віджета…").setOngoing(true).build()
        return ForegroundInfo(3,notification)
    }
    companion object {
        private const val PERIODIC="pulse-widget-periodic"
        private const val NOW="pulse-widget-now"
        private fun connected()=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(c: Context) {
            val ids=AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c,PulseWidget::class.java))
            if(ids.isEmpty()) return
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15,TimeUnit.MINUTES).setConstraints(connected()).build())
        }
        fun refresh(c: Context) {
            WorkManager.getInstance(c).enqueueUniqueWork(NOW,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(connected())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
        fun cancel(c: Context) {
            WorkManager.getInstance(c).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(c).cancelUniqueWork(NOW)
        }
    }
}
