package com.powerhub.pulse
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PulseWidget: AppWidgetProvider() {
    override fun onUpdate(c: Context,m: AppWidgetManager,ids: IntArray) { Store.init(c); refresh(c) }
    companion object {
        fun refresh(c: Context) {
            val m=AppWidgetManager.getInstance(c)
            val r=Store.state.value.reading
            val views=RemoteViews(c.packageName,R.layout.pulse_widget)
            views.setTextViewText(R.id.widget_value,if(r==null) "Ще немає даних" else "${r.soc.toInt()}%   •   ${r.loadWatts.toInt()} Вт")
            val at=r?.let { SimpleDateFormat("HH:mm • dd.MM",Locale.getDefault()).format(Date(it.measuredAt)) }
            views.setTextViewText(R.id.widget_status,if(r==null) "Відкрий додаток для підключення" else
                "${if(r.grid) "Мережа є" else "Від АКБ"} • вимір $at")
            views.setOnClickPendingIntent(R.id.widget_title,PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
            m.updateAppWidget(ComponentName(c,PulseWidget::class.java),views)
        }
    }
}
