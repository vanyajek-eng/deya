package com.powerhub.pulse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DeyeApiTest {
    private fun fixture(state: Int=1,grid: String="0",unit: String="kW")=JSONObject("""
      {"deviceDataList":[{"deviceSn":"123","deviceState":$state,"collectionTime":1700000000,
      "dataList":[{"key":"SOC","value":"47","unit":"%"},{"key":"BatteryVoltage","value":"51.3","unit":"V"},
      {"key":"TotalConsumptionPower","value":"3.2","unit":"$unit"},{"key":"GridVoltage","value":"$grid","unit":"V"}]}]}
    """)
    @Test fun cloudMapsUnitsAndUsesGridVoltage() {
        val r=DeyeApi.parseCloud(fixture(),Settings(inverterSn="123"))
        assertEquals(3200f,r.loadWatts,0.1f);assertEquals(47f,r.soc,0f)
        assertFalse(r.grid);assertEquals(1700000000000L,r.measuredAt)
    }
    @Test fun gridPresentFromVoltage() {
        assertTrue(DeyeApi.parseCloud(fixture(grid="230"),Settings(inverterSn="123")).grid)
    }
    @Test(expected=IllegalArgumentException::class) fun offlineDoesNotMeanPowerOutage() {
        DeyeApi.parseCloud(fixture(state=3),Settings(inverterSn="123"))
    }
    @Test(expected=IllegalStateException::class) fun missingPointDoesNotBecomeZero() {
        DeyeApi.parseCloud(fixture(),Settings(inverterSn="123",socKey="missing"))
    }
    @Test fun settingsRoundtrip() {
        val s=Settings(appId="abc",secret="secret",password="pass",lowSoc=17,highWatts=2500)
        assertEquals(s,Settings.parse(s.json()))
    }
}
