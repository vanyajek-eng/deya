package com.powerhub.deya.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class PowerHubWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences("powerhub_alert_state", Context.MODE_PRIVATE)
        val soc = prefs.getString("latest_soc", null)
        val voltage = prefs.getString("latest_voltage", null)
        val load = prefs.getString("latest_load", null)
        val grid = prefs.getString("latest_grid", null)
        provideContent {
            Column(GlanceModifier.background(ColorProvider(Color(0xFF14232D))).padding(16.dp)) {
                Text("PowerHub", style = TextStyle(color = ColorProvider(Color(0xFF58D6A5))))
                if (soc == null) {
                    Text("Налаштуй джерело даних у додатку", style = TextStyle(color = ColorProvider(Color.White)))
                } else {
                    Text("АКБ $soc% · $voltage В", style = TextStyle(color = ColorProvider(Color.White)))
                    Text("Навантаження $load кВт", style = TextStyle(color = ColorProvider(Color.White)))
                    Text("Мережа: " + if (grid == "true") "є" else "немає", style = TextStyle(color = ColorProvider(Color.White)))
                }
            }
        }
    }
}

class PowerHubWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PowerHubWidget()
}
