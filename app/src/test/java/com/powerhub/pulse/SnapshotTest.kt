package com.powerhub.pulse

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    private val settings=Settings(inverterSn="123")
    private fun fixture(state: Int=1)=JSONObject("""
        {"deviceDataList":[{"deviceSn":"123","deviceState":$state,"collectionTime":1700000000,"deviceType":"INVERTER","customMeta":"hello",
        "dataList":[{"key":"SOC","value":"47","unit":"%"},{"key":"BatteryVoltage","value":"51.3","unit":"V"},
        {"key":"TotalConsumptionPower","value":"3200","unit":"W"},{"key":"GridVoltageL1L2","value":"230","unit":"V"},
        {"key":"NewFutureSensor","value":"42","unit":"rpm"},{"key":"HMI","value":"v1.2","unit":""}]}]}
    """)
    @Test fun allUnknownAndTextParametersArePreserved() {
        val s=DeyeApi.parseSnapshot(fixture(),settings)
        assertEquals(6,s.points.size)
        assertEquals("42 rpm",s.find("NewFutureSensor")!!.display())
        assertEquals("v1.2",s.find("HMI")!!.display())
        assertEquals("hello",s.metadata["customMeta"])
    }
    @Test fun catalogueIncludesMissingFieldsWithoutFabricatedZeros() {
        val s=DeyeApi.parseSnapshot(fixture(),settings,listOf("SOC","MissingTemperature"))
        assertEquals(7,s.points.size)
        assertNull(s.find("MissingTemperature"))
        assertNull(s.points.first {it.key=="MissingTemperature"}.value)
    }
    @Test fun gridKeyFromActualDeviceIsDetectedAutomatically() {
        val s=DeyeApi.parseSnapshot(fixture(),settings.copy(gridKey="Grid"))
        assertEquals(true,s.metrics().grid)
    }
    @Test fun missingGridDoesNotHideOtherTelemetry() {
        val j=fixture();val d=j.getJSONArray("deviceDataList").getJSONObject(0)
        d.getJSONArray("dataList").remove(3)
        val s=DeyeApi.parseSnapshot(j,settings)
        assertNull(s.metrics().grid)
        assertEquals(47f,s.metrics().soc!!,0f)
        assertEquals(5,s.points.size)
    }
    @Test fun zeroGridPowerIsNotAnOutageIndicator() {
        val s=Snapshot("123",1000,1,"test",listOf(MeasurePoint("TotalGridPower","0","W")))
        assertNull(s.metrics().grid)
    }
    @Test fun offlineSnapshotRetainsFieldsButIsNeverFresh() {
        val s=DeyeApi.parseSnapshot(fixture(3),settings)
        assertEquals(6,s.points.size)
        assertFalse(s.fresh(1700000000000L,900))
    }
    @Test fun oldSnapshotIsDisplayableButNotFresh() {
        val s=DeyeApi.parseSnapshot(fixture(),settings)
        assertFalse(s.fresh(1700001000000L,900))
        assertEquals(47f,s.metrics().soc!!,0f)
    }
    @Test fun persistenceNeverIncludesCredentials() {
        val s=DeyeApi.parseSnapshot(fixture(),settings.copy(secret="supersecret",password="privatepassword"))
        val raw=s.json().toString()
        assertFalse(raw.contains("supersecret"));assertFalse(raw.contains("privatepassword"))
        assertEquals(6,Snapshot.parse(JSONObject(raw)).points.size)
    }
    @Test fun catalogSearchFindsTranslatedAndOriginalNames() {
        val points=DeyeApi.parseSnapshot(fixture(),settings).points
        assertEquals("BatteryVoltage",TelemetryCatalog.filtered(points,"напруга акумулятора","Усі",emptySet(),false).single().key)
        assertEquals("NewFutureSensor",TelemetryCatalog.filtered(points,"Future","Усі",emptySet(),false).single().key)
    }
    @Test fun favoritesAndGroupsWorkTogether() {
        val points=DeyeApi.parseSnapshot(fixture(),settings).points
        assertEquals("SOC",TelemetryCatalog.filtered(points,"","Акумулятор",setOf("SOC","HMI"),true).single().key)
    }
    @Test fun historyPreservesMeasurementTimestampAndUnits() {
        val sample=HistorySample.from(DeyeApi.parseSnapshot(fixture(),settings))
        assertEquals(1700000000000L,sample.at)
        assertEquals("W",sample.units["TotalConsumptionPower"])
        assertEquals(sample,HistorySample.parse(sample.json()))
    }
    @Test fun partialDataStillGeneratesLowBatteryAlarm() {
        val now=System.currentTimeMillis()
        val s=Snapshot("123",now,1,"test",listOf(MeasurePoint("SOC","10","%")))
        assertEquals("battery",AlarmEngine().acceptSnapshot(s,Settings(),1000).single().kind)
    }
}
