package com.powerhub.pulse

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Notifier {
    const val MONITOR="pulse_monitor"
    const val ALARM="pulse_alarm"
    fun channels(c: Context) {
        val m=c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(MONITOR,"Стан моніторингу",NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(ALARM,"Сигнали Deye",NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); description="Відключення мережі, заряд і навантаження"
        })
    }
    private fun open(c: Context)=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun ongoing(c: Context,text: String): Notification {
        val stop=PendingIntent.getService(c,1,Intent(c,MonitorService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(c,MONITOR).setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle("Deye Pulse • моніторинг").setContentText(text).setOngoing(true)
            .setContentIntent(open(c)).addAction(0,"Зупинити",stop).build()
    }
    fun notify(c: Context,a: Alarm) {
        channels(c)
        Store.log(c,a.title,a.detail)
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled()) {
            Store.status("Сповіщення заборонені в Android. Дозволь їх у налаштуваннях.")
            return
        }
        manager.notify(a.kind.hashCode(),NotificationCompat.Builder(c,ALARM).setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle(a.title).setContentText(a.detail).setStyle(NotificationCompat.BigTextStyle().bigText(a.detail))
            .setContentIntent(open(c)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }
    fun telegram(s: Settings,a: Alarm) {
        require(s.telegramToken.isNotBlank() && s.telegramChat.isNotBlank()) { "Вкажи bot token та chat ID" }
        val body=FormBody.Builder().add("chat_id",s.telegramChat.trim()).add("text",a.title+"\n"+a.detail).build()
        val client=OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("https://api.telegram.org/bot"+s.telegramToken.trim()+"/sendMessage").post(body).build()).execute().use {
            check(it.isSuccessful && JSONObject(it.body?.string() ?: "{}").optBoolean("ok")) {
                "Telegram не прийняв повідомлення (HTTP ${it.code}). Перевір token, chat ID та /start у боті."
            }
        }
    }
}
