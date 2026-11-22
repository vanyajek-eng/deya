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
    val events: List<LogEntry> = emptyList(), val history: List<Float> = emptyList()
)
object Store {
    val state = MutableStateFlow(ScreenState())
    private fun prefs(c: Context) = c.getSharedPreferences("pulse",Context.MODE_PRIVATE)
    @Synchronized fun init(c: Context) {
        if(state.value.events.isNotEmpty() || state.value.reading != null) return
        val p=prefs(c)
        val reading=runCatching { Reading.parse(JSONObject(p.getString("reading","")!!)) }.getOrNull()
        val logs=runCatching {
            val a=JSONArray(p.getString("events","[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { LogEntry(it.getLong("at"),it.getString("title"),it.getString("detail")) } }
        }.getOrDefault(emptyList())
        state.value=ScreenState(reading=reading,events=logs,lastReceived=p.getLong("received",0L))
    }
    fun settings(c: Context): Settings {
        val raw=prefs(c).getString("settings",null) ?: return Settings()
        return Settings.parse(JSONObject(decrypt(raw)))
    }
    fun save(c: Context,s: Settings) { prefs(c).edit().putString("settings",encrypt(s.json().toString())).commit() }
    fun enabled(c: Context) = prefs(c).getBoolean("enabled",false)
    fun enabled(c: Context,v: Boolean) { prefs(c).edit().putBoolean("enabled",v).commit() }
    fun alarmState(c: Context) = runCatching { JSONObject(prefs(c).getString("alarms","{}")!!) }.getOrDefault(JSONObject())
    fun alarmState(c: Context,j: JSONObject) { prefs(c).edit().putString("alarms",j.toString()).apply() }
    @Synchronized fun status(text: String,running: Boolean = state.value.running) { state.value=state.value.copy(status=text,running=running) }
    @Synchronized fun reading(c: Context,r: Reading) {
        val now=System.currentTimeMillis()
        val old=state.value
        val history=if(old.reading?.measuredAt != r.measuredAt) (old.history+r.loadWatts).takeLast(60) else old.history
        state.value=old.copy(reading=r,lastReceived=now,history=history)
        prefs(c).edit().putString("reading",r.json().toString()).putLong("received",now).apply()
        PulseWidget.refresh(c)
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
