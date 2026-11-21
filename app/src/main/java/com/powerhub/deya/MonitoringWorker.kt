package com.powerhub.deya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.powerhub.deya.widget.PowerHubWidget

/**
 * Periodic monitor. WorkManager runs periodic work no more often than every 15 minutes;
 * Android may defer it further to save battery.
 */
class MonitoringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val config = ConfigStore.load(applicationContext)
        if (config.mode != "HTTP_JSON" || config.endpoint.isBlank()) return Result.failure()

        val telemetry = try {
            withContext(Dispatchers.IO) { DataSource().read(config) }
        } catch (_: Exception) {
            return Result.retry()
        }

        val prefs = applicationContext.getSharedPreferences("powerhub_alert_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("latest_soc", "%.0f".format(telemetry.soc))
            .putString("latest_voltage", "%.1f".format(telemetry.batteryVoltage))
            .putString("latest_load", "%.2f".format(telemetry.loadKw))
            .putString("latest_grid", telemetry.gridPresent.toString())
            .apply()
        runCatching { PowerHubWidget().updateAll(applicationContext) }
        val lowNow = telemetry.soc <= config.lowSoc
        val overloadNow = !telemetry.gridPresent && telemetry.loadKw >= 3.0f
        val lowNew = lowNow && !prefs.getBoolean("low_soc_active", false)
        val overloadNew = overloadNow && !prefs.getBoolean("overload_active", false)

        // Persist transitions first so a Telegram outage cannot produce an alert on every retry.
        prefs.edit()
            .putBoolean("low_soc_active", lowNow && telemetry.soc <= config.lowSoc + 2f)
            .putBoolean("overload_active", overloadNow)
            .apply()

        if (lowNew) deliver("Низький заряд акумулятора: %.0f%%".format(telemetry.soc), config)
        if (overloadNew) deliver("Високе навантаження без мережі: %.2f кВт".format(telemetry.loadKw), config)
        return Result.success()
    }

    private suspend fun deliver(message: String, config: AppConfig) {
        showNotification(message)
        if (config.telegramToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
            withContext(Dispatchers.IO) { Telegram.send(config, message) }
        }
    }

    private fun showNotification(message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PowerHub alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PowerHub · попередження")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager.notify(message.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "powerhub_alerts"
    }
}
