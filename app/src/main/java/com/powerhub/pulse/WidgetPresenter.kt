package com.powerhub.pulse

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WidgetContent(
    val soc: String, val load: String, val volts: String, val grid: String,
    val time: String, val tone: String, val progress: Int, val ageMs: Long
)
object WidgetPresenter {
    fun present(r: Reading?, now: Long, maxAgeSeconds: Int, lowSoc: Int, highWatts: Int, running: Boolean): WidgetContent {
        if(r==null) return WidgetContent("—","—","—","Відкрий додаток для підключення","Ще немає вимірів","muted",0,0)
        val age=(now-r.measuredAt).coerceAtLeast(0)
        val stale=age>maxAgeSeconds*1000L || r.measuredAt-now>60000L
        val warning=r.soc<=lowSoc || (!r.grid && r.loadWatts>=highWatts)
        val grid=when {
            stale -> "Стан мережі невідомий • старі дані"
            !running -> if(r.grid) "Останній стан: мережа є" else "Останній стан: від АКБ"
            r.grid -> "● Мережа є"
            else -> "● Живлення від АКБ"
        }
        val tone=when { stale -> "stale"; !running -> "muted"; warning || !r.grid -> "warning"; else -> "ok" }
        val time=SimpleDateFormat("HH:mm • dd.MM",Locale.getDefault()).format(Date(r.measuredAt))
        return WidgetContent(
            "${r.soc.toInt()}%","%.2f кВт".format(r.loadWatts/1000f),"%.1f В".format(r.volts),
            grid,time,tone,r.soc.toInt().coerceIn(0,100),age
        )
    }
}
