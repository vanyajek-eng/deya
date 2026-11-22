package com.powerhub.pulse

import android.app.*
import android.content.*
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MonitorService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job?=null
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="STOP") {
            Store.enabled(this,false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        Store.init(this); Notifier.channels(this)
        startForeground(1,Notifier.ongoing(this,"З’єднання з інвертором…"))
        Store.enabled(this,true)
        if(job?.isActive!=true) {
            wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DeyePulse:monitor").apply { acquire() }
            job=scope.launch { loop() }
        }
        return START_STICKY
    }
    private suspend fun loop() {
        val api=DeyeApi()
        var engine=AlarmEngine(Store.alarmState(this))
        var sourceId: String?=null
        var disconnectedAt=0L
        var connectionAlarm=false
        Store.status("З’єднання з інвертором…",true)
        while(currentCoroutineContext().isActive) {
            var interval=15
            try {
                val s=Store.settings(this)
                interval=s.pollSeconds.coerceIn(3,300)
                val id=listOf(s.source,s.baseUrl,s.inverterSn,s.jsonUrl,s.socKey,s.voltageKey,s.loadKey,s.gridKey).joinToString("|")
                if(sourceId!=null && sourceId!=id) engine=AlarmEngine()
                sourceId=id
                val r=api.read(s)
                Store.reading(this,r)
                if(connectionAlarm) Store.log(this,"Зв’язок відновлено","Знову отримуємо дані інвертора.")
                disconnectedAt=0; connectionAlarm=false
                val age=((System.currentTimeMillis()-r.measuredAt)/1000).coerceAtLeast(0)
                val message="Підключено • даним $age с"
                Store.status(message,true)
                getSystemService(NotificationManager::class.java).notify(1,Notifier.ongoing(this,message))
                val alarms=engine.accept(r,s,SystemClock.elapsedRealtime())
                Store.alarmState(this,engine.state())
                for(a in alarms) {
                    Notifier.notify(this,a)
                    if(s.telegramToken.isNotBlank() && s.telegramChat.isNotBlank()) {
                        scope.launch {
                            try { Notifier.telegram(s,a) }
                            catch(e: Exception) { Store.log(this@MonitorService,"Помилка Telegram",e.message ?: "Невідома помилка") }
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                engine.gap()
                val text=e.message ?: "Не вдалося отримати дані"
                Store.status(text,true)
                getSystemService(NotificationManager::class.java).notify(1,Notifier.ongoing(this,"Немає актуальних даних • повторна спроба"))
                if(disconnectedAt==0L) disconnectedAt=SystemClock.elapsedRealtime()
                if(!connectionAlarm && SystemClock.elapsedRealtime()-disconnectedAt>=60000L) {
                    connectionAlarm=true
                    Notifier.notify(this,Alarm("connection","Втрачено зв’язок з Deye","Стан мережі невідомий. $text"))
                }
            }
            delay(interval*1000L)
        }
    }
    override fun onDestroy() {
        scope.cancel()
        wake?.let { if(it.isHeld) it.release() }
        Store.status("Моніторинг зупинено",false)
        PulseWidget.refresh(this)
        super.onDestroy()
    }
    companion object {
        fun start(c: Context) = ContextCompat.startForegroundService(c,Intent(c,MonitorService::class.java))
        fun stop(c: Context) {
            Store.enabled(c,false)
            c.stopService(Intent(c,MonitorService::class.java))
            Store.status("Моніторинг зупинено",false)
        }
    }
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        if(i.action!=Intent.ACTION_BOOT_COMPLETED || !Store.enabled(c)) return
        runCatching { if(Store.settings(c).boot) MonitorService.start(c) }
    }
}
