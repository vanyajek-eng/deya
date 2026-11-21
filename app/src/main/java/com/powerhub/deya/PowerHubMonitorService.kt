package com.powerhub.deya

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.powerhub.deya.widget.PowerHubWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerHubMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob = kotlinx.coroutines.Job()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, ongoingNotification("З'єднання з логером…"))
        if (!pollingJob.isActive) {
            pollingJob = serviceScope.launch {
                try {
                    pollLoop()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    updateState("Помилка з'єднання: ${error.message ?: "невідома помилка"}")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        while (serviceScope.isActive) {
            val config = ConfigStore.load(applicationContext)
            if (config.mode != "SOLARMAN_V5" || config.modbusHost.isBlank() || config.loggerSerial.isBlank()) {
                updateState("Заповни IP і серійний номер Wi‑Fi логера.")
                stopSelf()
                return
            }
            val read = withContext(Dispatchers.IO) {
                DataSource().read(config)
            }
            val prefs = getSharedPreferences("powerhub_alert_state", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("latest_soc", "%.0f".format(read.soc))
                .putString("latest_voltage", "%.1f".format(read.batteryVoltage))
                .putString("latest_load", "%.2f".format(read.loadKw))
                .putString("latest_grid", read.gridPresent.toString())
                .putLong("latest_updated", System.currentTimeMillis())
                .apply()
            runCatching { PowerHubWidget().updateAll(applicationContext) }
            updateState("Підключено · оновлено щойно")
            handleAlerts(read, config, prefs)
            delay(config.pollIntervalSeconds.coerceIn(3, 60) * 1000L)
        }
    }

    private suspend fun handleAlerts(t: Telemetry, config: AppConfig, prefs: android.content.SharedPreferences) {
        val low = t.soc <= config.lowSoc
        val overload = !t.gridPresent && t.loadKw * 1000 >= config.overloadWatts
        val lowNew = low && !prefs.getBoolean("low_soc_active", false)
        val overloadNew = overload && !prefs.getBoolean("overload_active", false)
        prefs.edit()
            .putBoolean("low_soc_active", low)
            .putBoolean("overload_active", overload)
            .apply()
        if (lowNew) alert("Низький заряд акумулятора: %.0f%%".format(t.soc), config)
        if (overloadNew) alert("Високе навантаження без мережі: %.2f кВт".format(t.loadKw), config)
    }

    private suspend fun alert(message: String, config: AppConfig) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(message.hashCode(), NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PowerHub · попередження")
            .setContentText(message)
            .setAutoCancel(true)
            .build())
        if (config.telegramToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
            runCatching { withContext(Dispatchers.IO) { Telegram.send(config, message) } }
        }
    }

    private fun updateState(text: String) {
        getSharedPreferences("powerhub_alert_state", Context.MODE_PRIVATE).edit()
            .putString("monitor_status", text).apply()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, ongoingNotification(text))
    }

    private fun ongoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PowerHub · моніторинг Deye")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "PowerHub monitor", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "PowerHub alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.powerhub.deya.START_MONITOR"
        const val ACTION_STOP = "com.powerhub.deya.STOP_MONITOR"
        private const val NOTIFICATION_ID = 9081
        private const val SERVICE_CHANNEL = "powerhub_monitor"
        private const val ALERT_CHANNEL = "powerhub_alerts"

        fun start(context: Context) {
            val intent = Intent(context, PowerHubMonitorService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PowerHubMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}

class PowerHubBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val config = ConfigStore.load(context)
                if (config.autoStart && config.mode == "SOLARMAN_V5") {
                    runCatching { PowerHubMonitorService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
