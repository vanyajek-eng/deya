package com.powerhub.pulse

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Sync {
    private val lock=Mutex()
    private val api=DeyeApi()
    suspend fun pull(c: Context,s: Settings): Snapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            Store.loading(true)
            try {
                val snapshot=api.fetch(s)
                Store.snapshot(c,snapshot)
                val age=((System.currentTimeMillis()-snapshot.measuredAt)/1000).coerceAtLeast(0)
                val message=when {
                    !snapshot.online -> "Логер офлайн • показано останні дані"
                    !snapshot.fresh(System.currentTimeMillis(),s.maxAgeSeconds) -> "Дані застарілі • останній вимір $age с тому"
                    else -> "Deye Cloud підключено • ${snapshot.points.count { it.value!=null }} показників"
                }
                Store.status(message)
                snapshot
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                Store.failure(c,e.message ?: "Не вдалося отримати дані")
                throw e
            } finally { Store.loading(false);PulseWidget.refresh(c) }
        }
    }
}
