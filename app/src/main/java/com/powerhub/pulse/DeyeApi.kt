package com.powerhub.pulse

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DeyeApi(suppliedClient: OkHttpClient? = null) {
    private val client=suppliedClient ?: OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var token=""
    private var expires=0L
    private var credentials=""
    private var catalogId=""
    private var catalogAt=0L
    private var catalogKeys=emptyList<String>()
    fun fetch(s: Settings): Snapshot {
        s.validate()
        return if(s.source=="CLOUD") cloud(s) else Snapshot.from(local(s)).copy(sn=s.inverterSn)
    }
    fun read(s: Settings): Reading = fetch(s).requireReading().validate(System.currentTimeMillis(),s.maxAgeSeconds*1000L)
    private fun post(url: String,body: JSONObject,bearer: String=""): JSONObject {
        val builder=Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        if(bearer.isNotBlank()) builder.header("Authorization","Bearer $bearer")
        client.newCall(builder.build()).execute().use {
            if(it.code==401) { token=""; expires=0; error("Авторизація завершилась. Повторна спроба автоматично.") }
            check(it.isSuccessful) { "Deye Cloud HTTP ${it.code}" }
            val j=JSONObject(it.body?.string() ?: error("Порожня відповідь Deye"))
            if(!j.optBoolean("success",false)) { token=""; expires=0 }
            check(j.optBoolean("success",false)) { "Deye API: ${j.optString("code","ERROR")}. Перевір сервер, дані акаунта та дозвіл API на керування." }
            return j
        }
    }
    private fun authenticate(s: Settings) {
        s.validate()
        val fingerprint=listOf(s.baseUrl,s.appId,s.secret,s.email,s.password,s.companyId).joinToString("\u0000")
        if(token.isBlank() || expires<System.currentTimeMillis() || credentials!=fingerprint) {
            val hashed=MessageDigest.getInstance("SHA-256").digest(s.password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val url=(s.baseUrl+"/v1.0/account/token").toHttpUrl().newBuilder().addQueryParameter("appId",s.appId.trim()).build()
            val auth=post(url.toString(),JSONObject().put("appSecret",s.secret.trim()).put("email",s.email.trim()).put("password",hashed).apply {if(s.companyId.isNotBlank()) put("companyId",s.companyId.trim().toLong())})
            token=auth.getString("accessToken")
            expires=System.currentTimeMillis()+(auth.optLong("expiresIn",3600).coerceAtLeast(120)-60)*1000L
            credentials=fingerprint
        }
    }
    private fun cloud(s: Settings): Snapshot {
        authenticate(s)
        val j=post(s.baseUrl+"/v1.0/device/latest",
            JSONObject().put("deviceList",JSONArray().put(s.inverterSn.trim())).put("deviceType","INVERTER"),token)
        val catalogKey=s.baseUrl+"|"+s.appId+"|"+s.email+"|"+s.inverterSn
        if(catalogId!=catalogKey || System.currentTimeMillis()-catalogAt>3600000L) {
            catalogKeys=runCatching {
                val catalog=post(s.baseUrl+"/v1.0/device/measurePoints",
                    JSONObject().put("deviceSn",s.inverterSn.trim()).put("deviceType","INVERTER"),token).getJSONArray("measurePoints")
                List(catalog.length()) { catalog.getString(it) }
            }.getOrDefault(emptyList())
            catalogId=catalogKey;catalogAt=System.currentTimeMillis()
        }
        return parseSnapshot(j,s,catalogKeys)
    }
    private fun local(s: Settings): Reading {
        val builder=Request.Builder().url(s.jsonUrl)
        val headers=JSONObject(s.jsonHeaders)
        headers.keys().forEach { builder.header(it,headers.getString(it)) }
        client.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Локальний API HTTP ${it.code}" }
            val j=JSONObject(it.body?.string() ?: error("Порожня відповідь API"))
            // Fixed, explicit local schema: power is watts, timestamp is Unix seconds.
            return Reading(j.getDouble("soc").toFloat(),j.getDouble("batteryVoltage").toFloat(),
                j.getDouble("loadWatts").toFloat(),j.getBoolean("gridPresent"),
                j.getLong("timestamp")*1000L,"Локальний API")
        }
    }
    fun setGridCharge(s: Settings,enabled: Boolean): String {
        require(s.source=="CLOUD") {"Перемикач доступний через Deye Cloud"}
        authenticate(s)
        val response=post(s.baseUrl+"/v1.0/order/battery/modeControl",gridChargeBody(s.inverterSn,enabled),token)
        val id=response.opt("orderId")?.toString()?.takeIf {it.matches(Regex("[0-9]+"))}
            ?: error("Deye не надав номер команди. Виконання не підтверджене.")
        return id
    }
    fun gridChargeOrder(s: Settings,id: String): JSONObject {
        require(id.matches(Regex("[0-9]+"))) {"Некоректний номер команди"}
        authenticate(s)
        val req=Request.Builder().url(s.baseUrl+"/v1.0/order/"+id).header("Authorization","Bearer $token").build()
        client.newCall(req).execute().use {r->
            check(r.isSuccessful) {"Deye: не вдалося перевірити команду (HTTP ${r.code})"}
            val j=JSONObject(r.body?.string() ?: "{}")
            check(j.optBoolean("success",false)) {"Deye не підтвердив стан команди: "+j.optString("code")}
            check(j.opt("orderId")?.toString()==id) {"Deye повернув інший номер команди"}
            return j
        }
    }
    companion object {
        fun gridChargeBody(sn: String,enabled: Boolean): JSONObject {
            require(sn.isNotBlank()) {"Вкажи SN інвертора"}
            return JSONObject().put("deviceSn",sn.trim()).put("batteryModeType","GRID_CHARGE").put("action",if(enabled) "on" else "off")
        }
        fun orderSucceeded(j: JSONObject): Boolean = j.optInt("status",-1)==666
        fun parseCloud(j: JSONObject,s: Settings): Reading {
            val snapshot=parseSnapshot(j,s)
            require(snapshot.online) { "Логер офлайн. Стан мережі невідомий." }
            return snapshot.requireReading()
        }
        fun parseSnapshot(j: JSONObject,s: Settings,catalog: List<String> = emptyList()): Snapshot {
            val devices=j.getJSONArray("deviceDataList")
            val d=(0 until devices.length()).map { devices.getJSONObject(it) }
                .firstOrNull { it.optString("deviceSn")==s.inverterSn.trim() }
                ?: error("Deye не повернув дані цього SN. Перевір доступ до інвертора.")
            val a=d.optJSONArray("dataList") ?: JSONArray()
            val points=List(a.length()) { index->
                val p=a.getJSONObject(index)
                MeasurePoint(p.getString("key"),if(p.isNull("value")) null else p.opt("value")?.toString(),p.optString("unit").takeUnless { it=="null" } ?: "")
            }
            val present=points.map { it.key }.toSet()
            val all=points+catalog.filter { it !in present }.map { MeasurePoint(it,null) }
            val meta=d.keys().asSequence().filter { it!="dataList" }.associateWith { d.opt(it).toString() }
            return Snapshot(d.optString("deviceSn"),d.optLong("collectionTime")*1000L,
                d.optInt("deviceState",3),"Deye Cloud",all,meta,s)
        }
    }
}
