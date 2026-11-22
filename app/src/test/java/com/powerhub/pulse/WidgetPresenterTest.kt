package com.powerhub.pulse

import org.junit.Assert.*
import org.junit.Test

class WidgetPresenterTest {
    private fun reading(grid: Boolean=true,soc: Float=75f,load: Float=1500f) = Reading(soc,52.4f,load,grid,1000000L,"Deye Cloud")
    @Test fun noReadingNeverInventsValues() {
        val w=WidgetPresenter.present(null,1000000L,900,20,3000,true)
        assertEquals("—",w.soc); assertEquals("muted",w.tone)
        assertEquals(0,w.progress)
    }
    @Test fun liveDataIncludesAllMetrics() {
        val w=WidgetPresenter.present(reading(),1030000L,900,20,3000,true)
        assertEquals("75%",w.soc)
        assertTrue(w.load.contains("кВт"))
        assertTrue(w.volts.contains("52"))
        assertEquals("ok",w.tone); assertEquals(30000L,w.ageMs)
        assertTrue(w.grid.contains("Мережа є"))
    }
    @Test fun staleDataNeverClaimsGridIsCurrentlyPresent() {
        val w=WidgetPresenter.present(reading(),2000000L,900,20,3000,true)
        assertEquals("stale",w.tone)
        assertTrue(w.grid.contains("невідомий"))
    }
    @Test fun stoppedMonitorLabelsLastKnownState() {
        val w=WidgetPresenter.present(reading(),1030000L,900,20,3000,false)
        assertEquals("muted",w.tone)
        assertTrue(w.grid.startsWith("Останній стан"))
    }
    @Test fun lowBatteryUsesConfiguredThreshold() {
        assertEquals("warning",WidgetPresenter.present(reading(soc=24f),1030000L,900,25,3000,true).tone)
    }
    @Test fun outageAndOverloadAreHighlighted() {
        val w=WidgetPresenter.present(reading(grid=false,load=3500f),1030000L,900,20,3000,true)
        assertEquals("warning",w.tone);assertTrue(w.grid.contains("АКБ"))
    }
}
