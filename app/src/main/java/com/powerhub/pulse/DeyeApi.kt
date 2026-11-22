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

class DeyeApi {
    private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private var token=""
    private var expires=0L
    private var credentials=""
    fun read(s: Settings): Reading {
        s.validate()
        val r=if(s.source=="CLOUD") cloud(s) else local(s)
        return r.validate(System.currentTimeMillis(),s.maxAgeSeconds*1000L)
    }
    private fun post(url: String,body: JSONObject,bearer: String=""): JSONObject {
        val builder=Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        if(bearer.isNotBlank()) builder.header("Authorization","Bearer $bearer")
        client.newCall(builder.build()).execute().use {
            if(it.code==401) { token=""; expires=0; error("Авторизація завершилась. Повторна спроба автоматично.") }
            check(it.isSuccessful) { "Deye Cloud HTTP ${it.code}" }
            val j=JSONObject(it.body?.string() ?: error("Порожня відповідь Deye"))
            check(j.optBoolean("success",false)) { "Deye API: ${j.optString("code","ERROR")}. Перевір сервер, доступ і дані акаунта." }
            return j
        }
    }
    private fun cloud(s: Settings): Reading {
        val fingerprint=listOf(s.baseUrl,s.appId,s.secret,s.email,s.password).joinToString("\u0000")
        if(token.isBlank() || expires<System.currentTimeMillis() || credentials!=fingerprint) {
            val hashed=MessageDigest.getInstance("SHA-256").digest(s.password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val url=(s.baseUrl+"/v1.0/account/token").toHttpUrl().newBuilder().addQueryParameter("appId",s.appId.trim()).build()
            val auth=post(url.toString(),JSONObject().put("appSecret",s.secret.trim()).put("email",s.email.trim()).put("password",hashed))
            token=auth.getString("accessToken")
            expires=System.currentTimeMillis()+(auth.optLong("expiresIn",3600).coerceAtLeast(120)-60)*1000L
            credentials=fingerprint
        }
        val j=post(s.baseUrl+"/v1.0/device/latest",
            JSONObject().put("deviceList",JSONArray().put(s.inverterSn.trim())).put("deviceType","INVERTER"),token)
        return parseCloud(j,s)
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
    companion object {
        fun parseCloud(j: JSONObject,s: Settings): Reading {
            val devices=j.getJSONArray("deviceDataList")
            val device=(0 until devices.length()).map { devices.getJSONObject(it) }
                .firstOrNull { it.optString("deviceSn")==s.inverterSn.trim() }
                ?: error("Deye не повернув дані цього SN. Перевір доступ до інвертора.")
            require(device.optInt("deviceState") in listOf(1,2)) { "Логер офлайн. Стан зовнішньої мережі невідомий." }
            val data=device.getJSONArray("dataList")
            val points=(0 until data.length()).map { data.getJSONObject(it) }.associateBy { it.getString("key") }
            fun point(key: String): JSONObject = points[key]
                ?: error("Немає показника $key. Доступні ключі: ${points.keys.joinToString().take(1200)}")
            fun number(key: String): Float = point(key).getString("value").toFloatOrNull()
                ?: error("Показник $key не є числом")
            val loadPoint=point(s.loadKey)
            val unit=loadPoint.optString("unit").ifBlank { s.cloudLoadUnit }
            val load=number(s.loadKey)*when(unit.lowercase()) {
                "kw" -> 1000f
                "w" -> 1f
                else -> error("Невідома одиниця потужності: $unit")
            }
            val grid=if(s.gridIsVoltage) number(s.gridKey)>=80f else
                when(point(s.gridKey).getString("value").lowercase()) {
                    "true","1","on" -> true
                    "false","0","off" -> false
                    else -> error("Невідомий стан мережі. Обери ключ напруги мережі.")
                }
            return Reading(number(s.socKey),number(s.voltageKey),load,grid,
                device.getLong("collectionTime")*1000L,"Deye Cloud")
        }
    }
}
