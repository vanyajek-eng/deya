package com.powerhub.deya.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.compose.ui.unit.dp

class PowerHubWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(GlanceModifier.background(Color(0xFF14232D)).padding(16.dp)) {
                Text("PowerHub")
                Text("Віджет моніторингу Deye")
                Text("Відкрий додаток, щоб налаштувати джерело даних")
            }
        }
    }
}
class PowerHubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PowerHubWidget()
}
