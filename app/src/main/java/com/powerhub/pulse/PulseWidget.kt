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
    override fun onUpdate(c: Context,m: AppWidgetManager,ids: IntArray) { Store.init(c); refresh(c) }
    override fun onAppWidgetOptionsChanged(c: Context,m: AppWidgetManager,id: Int,options: Bundle) { Store.init(c); refresh(c) }
    override fun onReceive(c: Context,i: Intent) {
        super.onReceive(c,i)
        if(i.action==PINNED) { Store.init(c); refresh(c) }
    }
    companion object {
        private const val PINNED="com.powerhub.pulse.WIDGET_PINNED"
        fun pin(c: Context): Boolean {
            val manager=AppWidgetManager.getInstance(c)
            if(!manager.isRequestPinAppWidgetSupported) return false
            val callback=PendingIntent.getBroadcast(c,71,
                Intent(c,PulseWidget::class.java).setAction(PINNED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return manager.requestPinAppWidget(ComponentName(c,PulseWidget::class.java),null,callback)
        }
        fun refresh(c: Context) {
            val manager=AppWidgetManager.getInstance(c)
            val ids=manager.getAppWidgetIds(ComponentName(c,PulseWidget::class.java))
            if(ids.isEmpty()) return
            val state=Store.state.value
            val limits=Store.widgetLimits(c)
            val content=WidgetPresenter.present(state.reading,System.currentTimeMillis(),limits.first,limits.second,limits.third,state.running)
            val color=Color.parseColor(when(content.tone) {
                "ok" -> "#B9F56B"
                "warning","stale" -> "#FFBB72"
                else -> "#93A4B3"
            })
            val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for(id in ids) {
                val compact=manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,160)<150
                val views=RemoteViews(c.packageName,if(compact) R.layout.pulse_widget_compact else R.layout.pulse_widget)
                views.setTextViewText(R.id.widget_soc,content.soc)
                views.setTextViewText(R.id.widget_load,content.load)
                views.setTextViewText(R.id.widget_volts,content.volts)
                views.setTextViewText(R.id.widget_grid,content.grid)
                views.setTextViewText(R.id.widget_time,content.time)
                views.setTextColor(R.id.widget_grid,color)
                views.setTextColor(R.id.widget_soc,color)
                views.setProgressBar(R.id.widget_battery,100,content.progress,false)
                views.setViewVisibility(R.id.widget_age,if(state.reading==null) View.GONE else View.VISIBLE)
                views.setChronometer(R.id.widget_age,SystemClock.elapsedRealtime()-content.ageMs,"Вік: %s",state.reading!=null)
                views.setOnClickPendingIntent(R.id.widget_root,open)
                manager.updateAppWidget(id,views)
            }
        }
    }
}
