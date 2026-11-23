package com.powerhub.pulse

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PulseUiTest {
    @get:Rule(order=0) val permission=GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var c: Context
    @Before fun seedTestTelemetry() {
        c=ApplicationProvider.getApplicationContext()
        val now=System.currentTimeMillis()
        val snapshot=Snapshot("TEST-INVERTER",now,1,"TEST ONLY",listOf(
            MeasurePoint("SOC","73","%"),MeasurePoint("BatteryVoltage","52.4","V"),
            MeasurePoint("TotalConsumptionPower","1340","W"),MeasurePoint("GridVoltageL1L2","231.4","V"),
            MeasurePoint("TotalDCInputPower","2450","W"),MeasurePoint("BatteryPower","-1100","W"),
            MeasurePoint("TotalGridPower","40","W"),MeasurePoint("DailyConsumption","8.4","kWh"),
            MeasurePoint("DailyChargingEnergy","4.7","kWh"),MeasurePoint("DailyDischargingEnergy","2.6","kWh"),
            MeasurePoint("NewFutureSensor","42","rpm")
        ))
        rule.runOnUiThread {
            Store.snapshot(c,snapshot)
            Store.status("Тестові дані інтерфейсу",false)
        }
        rule.waitForIdle()
    }
    private fun emitImage(file: File) {
        val chunks=android.util.Base64.encodeToString(file.readBytes(),android.util.Base64.NO_WRAP).chunked(1800)
        chunks.forEachIndexed {index,value -> android.util.Log.i("PULSE_UI",file.name+"|"+index+"|"+chunks.size+"|"+value);Thread.sleep(30) }
    }
    private fun screenshot(name: String) {
        val dir=File(c.getExternalFilesDir(null),"ui-validation").apply {mkdirs()}
        val file=File(dir,name)
        Assert.assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
        emitImage(file)
    }
    @Test fun overviewAndUnknownParameterRender() {
        rule.onNodeWithText("Енергія твого дому").assertIsDisplayed()
        rule.onNodeWithText("73%").assertIsDisplayed()
        screenshot("overview.png")
        rule.onNodeWithText("Параметри").performClick()
        rule.onNodeWithTag("parameter_search").performTextInput("NewFuture")
        rule.onNodeWithText("NewFutureSensor").assertIsDisplayed()
        rule.onNodeWithText("42 rpm").assertIsDisplayed()
        screenshot("parameters.png")
    }
    @Test fun bothWidgetLayoutsInflateAndShowData() {
        rule.runOnUiThread {
            for(compact in listOf(false,true)) {
                val view=PulseWidget.views(c,compact).apply(c,FrameLayout(c))
                Assert.assertEquals("73%",view.findViewById<TextView>(R.id.widget_value1).text.toString())
                Assert.assertTrue(view.findViewById<TextView>(R.id.widget_grid).text.contains("мережа"))
                Assert.assertNotNull(view.findViewById<View>(R.id.widget_refresh))
                val width=1000
                val height=if(compact) 430 else 690
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                view.layout(0,0,width,height)
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val dir=File(c.getExternalFilesDir(null),"ui-validation").apply {mkdirs()}
                val file=File(dir,if(compact) "widget-compact.png" else "widget.png")
                file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                emitImage(file)
                bitmap.recycle()
            }
        }
    }
}
