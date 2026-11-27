package com.powerhub.pulse

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PendingTelegram(val id: String,val at: Long,val title: String,val detail: String,val chat: String) {
    fun json()=JSONObject().put("id",id).put("at",at).put("title",title).put("detail",detail).put("chat",chat)
    fun text(now: Long): String = "$title\n$detail\nПодія: "+timeLabel(at)+
        if(now-at>60000L) "\nДоставлено із затримкою. Це стан на момент події." else ""
    companion object {
        fun parse(j: JSONObject)=PendingTelegram(j.getString("id"),j.getLong("at"),j.getString("title"),j.getString("detail"),j.getString("chat"))
    }
}
object TelegramOutbox {
    private const val WORK="pulse-telegram-delivery"
    val lock=Mutex()
    private fun prefs(c: Context)=c.getSharedPreferences("telegram_delivery",Context.MODE_PRIVATE)
    @Synchronized fun entries(c: Context): List<PendingTelegram> = runCatching {
        val a=JSONArray(prefs(c).getString("queue","[]"))
        List(a.length()) {PendingTelegram.parse(a.getJSONObject(it))}
    }.getOrDefault(emptyList())
    @Synchronized private fun write(c: Context,items: List<PendingTelegram>) {
        val a=JSONArray();items.forEach {a.put(it.json())}
        check(prefs(c).edit().putString("queue",a.toString()).commit()) {"Не вдалося зберегти чергу Telegram"}
    }
    @Synchronized fun remove(c: Context,id: String) {write(c,entries(c).filter {it.id!=id})}
    fun blocked(c: Context)=prefs(c).getBoolean("blocked",false)
    fun block(c: Context,value: Boolean) {prefs(c).edit().putBoolean("blocked",value).apply()}
    fun nextAttempt(c: Context)=prefs(c).getLong("nextAttempt",0)
    fun defer(c: Context,seconds: Int) {prefs(c).edit().putLong("nextAttempt",System.currentTimeMillis()+seconds.coerceIn(1,86400)*1000L).apply()}
    fun state(c: Context)=prefs(c).getString("state","Ще немає автоматичних доставок").orEmpty()
    fun state(c: Context,text: String) {prefs(c).edit().putString("state",text).apply()}
    fun enqueue(c: Context,s: Settings,a: Alarm) {
        if(s.telegramToken.isBlank() || s.telegramChat.isBlank()) {
            state(c,"Telegram не налаштований у збережених параметрах")
            Store.log(c,"Telegram не налаштований","Збережи bot token та Chat ID у вкладці «Ще».")
            return
        }
        synchronized(this) {
            val queue=entries(c)
            if(queue.size>=100) {
                Store.log(c,"Черга Telegram переповнена","Найстаріше недоставлене повідомлення вилучено.")
            }
            write(c,(queue+PendingTelegram(UUID.randomUUID().toString(),System.currentTimeMillis(),a.title,a.detail,s.telegramChat.trim())).takeLast(100))
        }
        state(c,"Очікує доставки: "+entries(c).size)
        kick(c)
    }
    fun kick(c: Context,delaySeconds: Long=0,resetError: Boolean=false) {
        if(resetError) block(c,false)
        val request=OneTimeWorkRequestBuilder<TelegramDeliveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .apply {if(delaySeconds==0L) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)}.build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
}
class TelegramDeliveryWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifier.channels(applicationContext)
        return ForegroundInfo(5,androidx.core.app.NotificationCompat.Builder(applicationContext,Notifier.MONITOR)
            .setSmallIcon(R.drawable.ic_pulse).setContentTitle("Deye Pulse").setContentText("Доставка повідомлень у Telegram…").build())
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        TelegramOutbox.lock.withLock {
            Store.init(applicationContext)
            if(TelegramOutbox.blocked(applicationContext)) return@withLock Result.success()
            if(TelegramOutbox.nextAttempt(applicationContext)>System.currentTimeMillis()) return@withLock Result.retry()
            val s=Store.settings(applicationContext)
            if(s.telegramToken.isBlank() || s.telegramChat.isBlank()) {
                TelegramOutbox.state(applicationContext,"Вкажи й збережи Telegram token та Chat ID")
                return@withLock Result.success()
            }
            while(true) {
                val item=TelegramOutbox.entries(applicationContext).firstOrNull() ?: break
                if(item.chat!=s.telegramChat.trim()) {
                    TelegramOutbox.state(applicationContext,"Черга належить іншому Chat ID. Поверни попередній Chat ID, щоб не переслати приватні події іншому одержувачу.")
                    return@withLock Result.success()
                }
                if(System.currentTimeMillis()-item.at>86400000L) {
                    TelegramOutbox.remove(applicationContext,item.id)
                    Store.log(applicationContext,"Telegram: застарілу подію пропущено",item.title+" • "+timeLabel(item.at))
                    continue
                }
                try {
                    TelegramTransport.send(s.telegramToken,item.chat,item.text(System.currentTimeMillis()))
                    TelegramOutbox.remove(applicationContext,item.id)
                    TelegramOutbox.state(applicationContext,"Доставлено: "+timeLabel(System.currentTimeMillis()))
                    Store.log(applicationContext,"Доставлено в Telegram",item.title)
                } catch(e: kotlinx.coroutines.CancellationException) {throw e}
                catch(e: TelegramFailure) {
                    TelegramOutbox.state(applicationContext,e.message.orEmpty())
                    Store.log(applicationContext,"Telegram: доставка не вдалася",e.message.orEmpty())
                    if(!e.retryable) {TelegramOutbox.block(applicationContext,true);return@withLock Result.success()}
                    if(e.retryAfterSeconds>0) {
                        TelegramOutbox.defer(applicationContext,e.retryAfterSeconds)
                        return@withLock Result.retry()
                    }
                    return@withLock Result.retry()
                } catch(e: java.io.IOException) {
                    TelegramOutbox.state(applicationContext,"Немає зв’язку з Telegram. Повторимо доставку.")
                    return@withLock Result.retry()
                }
            }
            Result.success()
        }
    }
}
