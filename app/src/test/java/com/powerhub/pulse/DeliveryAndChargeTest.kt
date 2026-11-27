package com.powerhub.pulse

import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeliveryAndChargeTest {
    @Test fun telegramSuccessIsAccepted() {TelegramTransport.result(200,"""{"ok":true,"result":{"message_id":1}}""")}
    @Test fun telegramReportsPermanentChatErrors() {
        try {TelegramTransport.result(400,"""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""");fail()}
        catch(e: TelegramFailure) {assertFalse(e.retryable);assertTrue(e.message!!.contains("chat not found"))}
    }
    @Test fun telegramRespectsRateLimit() {
        try {TelegramTransport.result(429,"""{"ok":false,"error_code":429,"parameters":{"retry_after":90}}""");fail()}
        catch(e: TelegramFailure) {assertTrue(e.retryable);assertEquals(90,e.retryAfterSeconds)}
    }
    @Test fun telegramServerFailuresCanBeRetried() {
        try {TelegramTransport.result(503,"""{"ok":false,"error_code":503}""");fail()}
        catch(e: TelegramFailure) {assertTrue(e.retryable)}
    }
    @Test fun gridChargePayloadCannotChangeOtherSettings() {
        val on=DeyeApi.gridChargeBody("123",true)
        assertEquals(setOf("deviceSn","batteryModeType","action"),on.keys().asSequence().toSet())
        assertEquals("GRID_CHARGE",on.getString("batteryModeType"))
        assertEquals("on",on.getString("action"))
        assertEquals("off",DeyeApi.gridChargeBody("123",false).getString("action"))
    }
    @Test fun acceptedCommandIsNotExecutionConfirmation() {
        assertFalse(DeyeApi.orderSucceeded(JSONObject("""{"success":true,"orderId":123}""")))
        assertFalse(DeyeApi.orderSucceeded(JSONObject("""{"success":true,"status":100}""")))
        assertFalse(DeyeApi.orderSucceeded(JSONObject("""{"success":true,"status":500}""")))
        assertTrue(DeyeApi.orderSucceeded(JSONObject("""{"success":true,"status":666}""")))
    }
    private val settings=Settings(appId="test-app",secret="not-a-real-secret",email="test@example.com",password="not-a-real-password",inverterSn="123")
    @Test fun commandUsesOfficialEndpointAndWaitsForOrderReadback() {
        val requests=mutableListOf<Request>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val req=chain.request();requests+=req
            val body=when(req.url.encodedPath) {
                "/v1.0/account/token" -> """{"success":true,"accessToken":"fake","expiresIn":3600}"""
                "/v1.0/order/battery/modeControl" -> """{"success":true,"orderId":15}"""
                "/v1.0/order/15" -> """{"success":true,"orderId":15,"status":666}"""
                else -> error("Unexpected request; test never calls a real server")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.parse("application/json"),body)).build()
        }.build()
        val api=DeyeApi(client)
        val id=api.setGridCharge(settings,false)
        assertEquals("15",id)
        assertEquals(2,requests.size)
        val buffer=Buffer();requests.last().body!!.writeTo(buffer)
        assertEquals("off",JSONObject(buffer.readUtf8()).getString("action"))
        assertTrue(DeyeApi.orderSucceeded(api.gridChargeOrder(settings,id)))
        assertEquals("GET",requests.last().method)
        assertEquals(3,requests.size)
    }
    @Test fun invalidOrderIdNeverCreatesNetworkTraffic() {
        val client=OkHttpClient.Builder().addInterceptor {error("Network must not be used")}.build()
        try {DeyeApi(client).gridChargeOrder(settings,"../wrong");fail()}
        catch(e: IllegalArgumentException) {assertTrue(e.message!!.contains("номер"))}
    }
    @Test fun chargeStateRoundTripRetainsPendingOrder() {
        val state=ChargeState("owner",true,1000,true,"15",false,"Waiting")
        assertEquals(state,ChargeState.parse(state.json()))
    }
    @Test fun fastCloudAlertUsesFirstNewMeasurement() {
        val now=System.currentTimeMillis()
        fun snapshot(at: Long,grid: String)=Snapshot("123",at,1,"test",listOf(MeasurePoint("GridVoltageL1L2",grid,"V")))
        val engine=AlarmEngine()
        engine.acceptSnapshot(snapshot(now-60000,"230"),Settings(fastCloudAlerts=true),1000)
        assertEquals("grid",engine.acceptSnapshot(snapshot(now-50000,"0"),Settings(fastCloudAlerts=true),2000).single().kind)
        assertTrue(engine.acceptSnapshot(snapshot(now-50000,"0"),Settings(fastCloudAlerts=true),3000).isEmpty())
    }
    @Test fun confirmationModeStillRequiresDifferentMeasurements() {
        val now=System.currentTimeMillis()
        val cfg=Settings(fastCloudAlerts=false)
        fun snapshot(at: Long,grid: String)=Snapshot("123",at,1,"test",listOf(MeasurePoint("GridVoltageL1L2",grid,"V")))
        val engine=AlarmEngine()
        engine.acceptSnapshot(snapshot(now-60000,"230"),cfg,1000)
        assertTrue(engine.acceptSnapshot(snapshot(now-50000,"0"),cfg,2000).isEmpty())
        assertTrue(engine.acceptSnapshot(snapshot(now-50000,"0"),cfg,3000).isEmpty())
        assertEquals("grid",engine.acceptSnapshot(snapshot(now-20000,"0"),cfg,4000).single().kind)
    }
    @Test fun offlineCachedDataNeverProducesGridAlarm() {
        val now=System.currentTimeMillis()
        val snapshot=Snapshot("123",now,3,"test",listOf(MeasurePoint("GridVoltageL1L2","0","V")))
        assertTrue(AlarmEngine().acceptSnapshot(snapshot,Settings(),1000).isEmpty())
    }
    @Test fun newPreferencesSurvivePersistence() {
        val cfg=Settings(fastCloudAlerts=false,companyId="1234")
        assertEquals(cfg,Settings.parse(cfg.json()))
    }
}
