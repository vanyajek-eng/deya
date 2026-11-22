package com.powerhub.pulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

class PulseWidget: AppWidgetProvider() {
    override fun onEnabled(c: Context) {WidgetRefreshWorker.schedule(c)}
    override fun onDisabled(c: Context) {WidgetRefreshWorker.cancel(c)}
    override fun onUpdate(c: Context,m: AppWidgetManager,ids: IntArray) {
        Store.init(c);refresh(c);WidgetRefreshWorker.schedule(c);WidgetRefreshWorker.refresh(c)
    }
    override fun onAppWidgetOptionsChanged(c: Context,m: AppWidgetManager,id: Int,options: Bundle) {Store.init(c);refresh(c)}
    override fun onReceive(c: Context,i: Intent) {
        super.onReceive(c,i)
        when(i.action) {
            PINNED -> {Store.init(c);refresh(c);WidgetRefreshWorker.schedule(c)}
            REFRESH -> {Store.init(c);WidgetRefreshWorker.refresh(c)}
        }
    }
    companion object {
        private const val PINNED="com.powerhub.pulse.WIDGET_PINNED"
        private const val REFRESH="com.powerhub.pulse.WIDGET_REFRESH"
        fun pin(c: Context): Boolean {
            val m=AppWidgetManager.getInstance(c)
            if(!m.isRequestPinAppWidgetSupported) return false
            val callback=PendingIntent.getBroadcast(c,71,Intent(c,PulseWidget::class.java).setAction(PINNED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return m.requestPinAppWidget(ComponentName(c,PulseWidget::class.java),null,callback)
        }
        fun views(c: Context,compact: Boolean): RemoteViews {
            val state=Store.state.value
            val snapshot=state.snapshot
            val limits=Store.widgetLimits(c)
            val fresh=snapshot?.fresh(System.currentTimeMillis(),limits.first)==true && state.lastError==null
            val metrics=snapshot?.metrics()
            val color=Color.parseColor(if(fresh && metrics?.grid==true) "#7DEDD0" else "#FFC282")
            val views=RemoteViews(c.packageName,if(compact) R.layout.pulse_widget_compact else R.layout.pulse_widget)
            val keys=Store.widgetKeys(c)
            val labels=listOf(R.id.widget_label1,R.id.widget_label2,R.id.widget_label3,R.id.widget_label4)
            val values=listOf(R.id.widget_value1,R.id.widget_value2,R.id.widget_value3,R.id.widget_value4)
            val defaultKeys=listOf("SOC","TotalConsumptionPower","BatteryVoltage","TotalDCInputPower")
            for(i in 0..3) {
                val key=keys.getOrNull(i) ?: defaultKeys[i]
                val point=snapshot?.find(key)
                val label=when(key) {"SOC"->"ЗАРЯД АКБ";"TotalConsumptionPower"->"СПОЖИВАННЯ";"BatteryVoltage"->"НАПРУГА АКБ";"TotalDCInputPower"->"СОНЦЕ";else->TelemetryCatalog.title(key).uppercase()}
                val display=when {
                    point?.value==null -> "—"
                    Snapshot.normalize(key)=="soc" -> point.number?.let {"${it.toInt()}%"} ?: point.display()
                    point.unit.lowercase() in listOf("w","kw") -> powerLabel(snapshot.watts(key))
                    else -> point.display()
                }
                views.setTextViewText(labels[i],label)
                views.setTextViewText(values[i],display)
            }
            val status=when {
                snapshot==null -> "Підключи інвертор у додатку"
                !fresh -> "Стан мережі невідомий • старі дані"
                metrics?.grid==null -> "Мережа: дані недоступні"
                !state.running -> if(metrics.grid) "Останній вимір: мережа є" else "Останній вимір: від АКБ"
                metrics.grid -> "● Зовнішня мережа є"
                else -> "● Живлення від АКБ"
            }
            views.setTextViewText(R.id.widget_grid,status)
            views.setTextColor(R.id.widget_grid,color)
            views.setProgressBar(R.id.widget_battery,100,metrics?.soc?.toInt() ?: 0,false)
            views.setTextViewText(R.id.widget_time,if(snapshot==null) "Ще немає вимірів" else timeLabelForWidget(snapshot.measuredAt))
            views.setViewVisibility(R.id.widget_age,if(snapshot==null || snapshot.measuredAt<=0) View.GONE else View.VISIBLE)
            val age=if(snapshot==null) 0 else (System.currentTimeMillis()-snapshot.measuredAt).coerceAtLeast(0)
            views.setChronometer(R.id.widget_age,SystemClock.elapsedRealtime()-age,"Вік: %s",snapshot!=null)
            val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val refresh=PendingIntent.getBroadcast(c,72,Intent(c,PulseWidget::class.java).setAction(REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root,open)
            views.setOnClickPendingIntent(R.id.widget_refresh,refresh)
            return views
        }
        private fun timeLabelForWidget(at: Long)=if(at<=0) "Час виміру невідомий" else
            java.text.SimpleDateFormat("HH:mm • dd.MM",java.util.Locale.getDefault()).format(java.util.Date(at))
        fun refresh(c: Context) {
            val m=AppWidgetManager.getInstance(c)
            m.getAppWidgetIds(ComponentName(c,PulseWidget::class.java)).forEach {id->
                val compact=m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180)<165
                m.updateAppWidget(id,views(c,compact))
            }
        }
    }
}
