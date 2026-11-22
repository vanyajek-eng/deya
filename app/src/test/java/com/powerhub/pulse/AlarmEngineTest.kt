package com.powerhub.pulse
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AlarmEngineTest {
    private val s=Settings()
    private fun r(t: Long,grid: Boolean=true,soc: Float=80f,load: Float=500f)=Reading(soc,52f,load,grid,t,"test")
    @Test fun gridRequiresThirtySecondsAndNewMeasurements() {
        val e=AlarmEngine()
        e.accept(r(1000),s,1000)
        assertTrue(e.accept(r(2000,false),s,2000).isEmpty())
        assertTrue(e.accept(r(2000,false),s,40000).isEmpty())
        assertTrue(e.accept(r(31000,false),s,41000).isEmpty())
        assertEquals("grid",e.accept(r(32000,false),s,42000).single().kind)
        assertTrue(e.accept(r(33000,false),s,43000).isEmpty())
    }
    @Test fun shortOutageDoesNotAlert() {
        val e=AlarmEngine()
        e.accept(r(1000),s,1000);e.accept(r(2000,false),s,2000)
        assertTrue(e.accept(r(20000,true),s,20000).isEmpty())
    }
    @Test fun returnOfGridIsConfirmed() {
        val e=AlarmEngine(JSONObject().put("stable",false))
        assertTrue(e.accept(r(1000),s,1000).isEmpty())
        assertEquals("Світло повернулося",e.accept(r(31000),s,31000).single().title)
    }
    @Test fun gapResetsPendingOutage() {
        val e=AlarmEngine()
        e.accept(r(1000),s,1000);e.accept(r(2000,false),s,2000);e.gap()
        assertTrue(e.accept(r(50000,false),s,50000).isEmpty())
    }
    @Test fun lowSocHasHysteresisAndSurvivesRestart() {
        val e=AlarmEngine()
        assertEquals("battery",e.accept(r(1000,soc=20f),s,1000).single().kind)
        val restored=AlarmEngine(e.state())
        assertTrue(restored.accept(r(2000,soc=19f),s,2000).isEmpty())
        assertTrue(restored.accept(r(3000,soc=21f),s,3000).isEmpty())
        assertTrue(restored.accept(r(4000,soc=20f),s,4000).isEmpty())
        restored.accept(r(5000,soc=23f),s,5000)
        assertEquals("battery",restored.accept(r(6000,soc=20f),s,6000).single().kind)
    }
    @Test fun overloadOnlyOffGridAndUsesConfiguredThreshold() {
        val e=AlarmEngine()
        val cfg=s.copy(highWatts=4000)
        assertTrue(e.accept(r(1000,load=5000f),cfg,1000).isEmpty())
        assertTrue(e.accept(r(2000,false,load=3500f),cfg,2000).isEmpty())
        assertEquals("load",e.accept(r(3000,false,load=4000f),cfg,3000).single().kind)
        assertTrue(e.accept(r(4000,false,load=3950f),cfg,4000).isEmpty())
        assertTrue(e.accept(r(5000,false,load=4000f),cfg,5000).isEmpty())
    }
    @Test fun initialOfflineIsReportedAfterConfirmation() {
        val e=AlarmEngine()
        assertTrue(e.accept(r(1000,false),s,1000).isEmpty())
        assertEquals("grid",e.accept(r(31000,false),s,31000).single().kind)
    }
    @Test(expected=IllegalArgumentException::class) fun staleDataRejected() {
        r(1000).validate(700000,600000)
    }
    @Test(expected=IllegalArgumentException::class) fun impossibleSocRejected() {
        r(1000,soc=101f).validate(1000,600000)
    }
}
