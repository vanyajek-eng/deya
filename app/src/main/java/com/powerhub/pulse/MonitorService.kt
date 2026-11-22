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
            Store.enabled(this,false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY
        }
        Store.init(this);Notifier.channels(this)
        startForeground(1,Notifier.ongoing(this,"Підключення до Deye…"));Store.enabled(this,true)
        if(job?.isActive!=true) {
            wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DeyePulse:monitor")
                .apply {setReferenceCounted(false);acquire(600000L)}
            job=scope.launch {loop()}
        }
        return START_STICKY
    }
    private fun deliver(a: Alarm,s: Settings?) {
        Notifier.notify(this,a)
        if(s!=null && s.telegramToken.isNotBlank() && s.telegramChat.isNotBlank()) scope.launch {
            try {Notifier.telegram(s,a)}
            catch(e: Exception) {Store.log(this@MonitorService,"Помилка Telegram",e.message ?: "Не доставлено")}
        }
    }
    private suspend fun loop() {
        var engine=AlarmEngine(Store.alarmState(this))
        var sourceId: String?=null
        var problemAt=0L
        var problemSent=false
        var config: Settings?=null
        Store.status("Підключення до Deye…",true)
        while(currentCoroutineContext().isActive) {
            wake?.acquire(600000L)
            var interval=15
            try {
                val s=Store.settings(this);config=s
                interval=s.pollSeconds.coerceIn(3,300)
                val identity=listOf(s.source,s.baseUrl,s.inverterSn,s.jsonUrl,s.socKey,s.gridKey,s.gridIsVoltage.toString()).joinToString("|")
                if(sourceId!=null && identity!=sourceId) engine=AlarmEngine()
                sourceId=identity
                val snapshot=Sync.pull(this,s)
                if(snapshot.fresh(System.currentTimeMillis(),s.maxAgeSeconds)) {
                    if(problemSent) Store.log(this,"Дані знову оновлюються","Зв’язок із Deye відновлено.")
                    problemAt=0L;problemSent=false
                    engine.acceptSnapshot(snapshot,s,SystemClock.elapsedRealtime()).forEach {deliver(it,s)}
                    Store.alarmState(this,engine.state())
                } else {
                    engine.gap()
                    if(problemAt==0L) problemAt=SystemClock.elapsedRealtime()
                    if(!problemSent && SystemClock.elapsedRealtime()-problemAt>=60000L) {
                        problemSent=true
                        deliver(Alarm("connection","Немає свіжих даних Deye","Стан мережі не підтверджений. Перевір логер та інтернет."),s)
                    }
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {
                engine.gap();Store.failure(this,e.message ?: "Помилка отримання даних")
                if(problemAt==0L) problemAt=SystemClock.elapsedRealtime()
                if(!problemSent && SystemClock.elapsedRealtime()-problemAt>=60000L) {
                    problemSent=true
                    deliver(Alarm("connection","Втрачено зв’язок з Deye","Стан мережі невідомий. Перевір підключення."),config)
                }
            }
            getSystemService(NotificationManager::class.java).notify(1,Notifier.ongoing(this,Store.state.value.status))
            runCatching {PulseWidget.refresh(this)}
            delay(interval*1000L)
        }
    }
    override fun onDestroy() {
        scope.cancel();wake?.let {if(it.isHeld) it.release()}
        Store.status("Постійний моніторинг призупинено",false);PulseWidget.refresh(this);super.onDestroy()
    }
    companion object {
        fun start(c: Context)=ContextCompat.startForegroundService(c,Intent(c,MonitorService::class.java))
        fun stop(c: Context) {
            Store.enabled(c,false);c.stopService(Intent(c,MonitorService::class.java))
            Store.status("Постійний моніторинг призупинено",false);PulseWidget.refresh(c)
        }
    }
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) {
        if(i.action!=Intent.ACTION_BOOT_COMPLETED) return
        WidgetRefreshWorker.schedule(c)
        if(Store.enabled(c)) runCatching {if(Store.settings(c).boot) MonitorService.start(c)}
    }
}
