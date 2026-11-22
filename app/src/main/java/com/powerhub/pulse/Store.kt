package com.powerhub.pulse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LogEntry(val at: Long, val title: String, val detail: String)
data class ScreenState(
    val reading: Reading? = null, val running: Boolean = false,
    val status: String = "Підключи свій Deye", val lastReceived: Long = 0L,
    val events: List<LogEntry> = emptyList(), val history: List<Float> = emptyList(),
    val snapshot: Snapshot? = null, val samples: List<HistorySample> = emptyList(),
    val loading: Boolean = false, val lastError: String? = null
)
object Store {
    val state = MutableStateFlow(ScreenState())
    private var initialized=false
    private fun prefs(c: Context) = c.getSharedPreferences("pulse",Context.MODE_PRIVATE)
    @Synchronized fun init(c: Context) {
        if(initialized) return
        initialized=true
        val p=prefs(c)
        val reading=runCatching { Reading.parse(JSONObject(p.getString("reading","")!!)) }.getOrNull()
        val logs=runCatching {
            val a=JSONArray(p.getString("events","[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { LogEntry(it.getLong("at"),it.getString("title"),it.getString("detail")) } }
        }.getOrDefault(emptyList())
        val config=runCatching { settings(c) }.getOrDefault(Settings())
        val snapshot=runCatching { Snapshot.parse(JSONObject(p.getString("snapshot","")!!),config) }.getOrNull()
            ?: reading?.let { Snapshot.from(it) }
        val samples=runCatching {
            val a=JSONArray(p.getString("samples","[]"))
            List(a.length()) { HistorySample.parse(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        state.value=ScreenState(reading=reading,events=logs,lastReceived=p.getLong("received",0L),snapshot=snapshot,samples=samples)
    }
    fun settings(c: Context): Settings {
        val raw=prefs(c).getString("settings",null) ?: return Settings()
        return Settings.parse(JSONObject(decrypt(raw)))
    }
    fun save(c: Context,s: Settings) {
        val identity=s.source+"|"+s.baseUrl+"|"+s.inverterSn+"|"+s.jsonUrl
        val previous=prefs(c).getString("sourceIdentity",identity)
        if(previous!=identity) {
            prefs(c).edit().remove("snapshot").remove("reading").remove("samples").remove("alarms").commit()
            state.value=state.value.copy(reading=null,snapshot=null,samples=emptyList(),history=emptyList(),lastReceived=0)
        }
        prefs(c).edit().putString("settings",encrypt(s.json().toString())).putString("sourceIdentity",identity)
            .putInt("widgetMaxAge",s.maxAgeSeconds).putInt("widgetLowSoc",s.lowSoc).putInt("widgetHighWatts",s.highWatts).commit()
        PulseWidget.refresh(c)
    }
    fun widgetLimits(c: Context): Triple<Int,Int,Int> {
        val p=prefs(c)
        if(!p.contains("widgetMaxAge")) {
            val s=runCatching { settings(c) }.getOrDefault(Settings())
            p.edit().putInt("widgetMaxAge",s.maxAgeSeconds).putInt("widgetLowSoc",s.lowSoc).putInt("widgetHighWatts",s.highWatts).apply()
        }
        return Triple(p.getInt("widgetMaxAge",600),p.getInt("widgetLowSoc",20),p.getInt("widgetHighWatts",3000))
    }
    fun enabled(c: Context) = prefs(c).getBoolean("enabled",false)
    fun enabled(c: Context,v: Boolean) { prefs(c).edit().putBoolean("enabled",v).commit() }
    fun alarmState(c: Context) = runCatching { JSONObject(prefs(c).getString("alarms","{}")!!) }.getOrDefault(JSONObject())
    fun alarmState(c: Context,j: JSONObject) { prefs(c).edit().putString("alarms",j.toString()).apply() }
    @Synchronized fun status(text: String,running: Boolean = state.value.running) { state.value=state.value.copy(status=text,running=running) }
    @Synchronized fun loading(v: Boolean) { state.value=state.value.copy(loading=v) }
    @Synchronized fun failure(c: Context,text: String) {
        state.value=state.value.copy(status=text,lastError=text)
        PulseWidget.refresh(c)
    }
    @Synchronized fun reading(c: Context,r: Reading) { snapshot(c,Snapshot.from(r)) }
    @Synchronized fun snapshot(c: Context,s: Snapshot) {
        val now=System.currentTimeMillis()
        val old=state.value
        val validTime=s.measuredAt>0 && s.measuredAt<=now+60000L
        val latest=old.samples.lastOrNull()?.at ?: 0L
        val numeric=s.points.any { it.number!=null }
        val samples=if(validTime && numeric && s.online && s.measuredAt>latest)
            (old.samples+HistorySample.from(s)).takeLast(288) else old.samples
        val compatible=runCatching { s.requireReading() }.getOrNull()
        val loadHistory=samples.mapNotNull { sample ->
            sample.values.entries.firstOrNull { Snapshot.normalize(it.key)==Snapshot.normalize(s.mapping.loadKey) }?.value?.toFloat()?.div(
                if(s.find(s.mapping.loadKey,"TotalConsumptionPower")?.unit?.lowercase()=="kw") 1f else 1000f)
        }
        state.value=old.copy(reading=compatible,snapshot=s,lastReceived=now,samples=samples,history=loadHistory,lastError=null)
        val edit=prefs(c).edit().putString("snapshot",s.json().toString()).putLong("received",now)
        compatible?.let { edit.putString("reading",it.json().toString()) }
        if(samples!==old.samples) {
            val a=JSONArray();samples.forEach { a.put(it.json()) };edit.putString("samples",a.toString())
        }
        edit.apply()
        PulseWidget.refresh(c)
    }
    fun favorites(c: Context): Set<String> = prefs(c).getStringSet("favorites",setOf("SOC","BatteryVoltage","TotalConsumptionPower","GridVoltageL1L2"))!!.toSet()
    fun favorite(c: Context,key: String) {
        val set=favorites(c).toMutableSet()
        if(!set.add(key)) set.remove(key)
        prefs(c).edit().putStringSet("favorites",set).apply()
    }
    fun widgetKeys(c: Context): List<String> {
        val raw=prefs(c).getString("widgetKeys",null)
        return runCatching { val a=JSONArray(raw ?: """["SOC","TotalConsumptionPower","BatteryVoltage","TotalDCInputPower"]""");List(a.length()) { a.getString(it) } }
            .getOrDefault(listOf("SOC","TotalConsumptionPower","BatteryVoltage","TotalDCInputPower"))
    }
    fun widgetKeys(c: Context,keys: List<String>) {
        val a=JSONArray();keys.take(4).forEach { a.put(it) };prefs(c).edit().putString("widgetKeys",a.toString()).apply();PulseWidget.refresh(c)
    }
    @Synchronized fun log(c: Context,title: String,detail: String) {
        val logs=(listOf(LogEntry(System.currentTimeMillis(),title,detail))+state.value.events).take(100)
        state.value=state.value.copy(events=logs)
        val a=JSONArray()
        logs.forEach { a.put(JSONObject().put("at",it.at).put("title",it.title).put("detail",it.detail)) }
        prefs(c).edit().putString("events",a.toString()).apply()
    }
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("pulse-v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pulse-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes=Base64.decode(value,Base64.NO_WRAP)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
}
