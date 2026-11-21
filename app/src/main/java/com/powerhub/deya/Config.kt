package com.powerhub.deya

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.powerHubStore by preferencesDataStore(name = "powerhub_settings")

data class AppConfig(
    val mode: String = "DEMO",
    val endpoint: String = "",
    val method: String = "GET",
    val headersJson: String = "{}",
    val body: String = "",
    val socPath: String = "battery.soc",
    val voltagePath: String = "battery.voltage",
    val loadPath: String = "inverter.load_power",
    val gridPath: String = "grid.connected",
    val lowSoc: Float = 30f,
    val overloadWatts: Int = 3000,
    val pollIntervalSeconds: Int = 6,
    val autoStart: Boolean = true,
    val threePhase: Boolean = false,
    val inverterSerial: String = "",
    val loggerSerial: String = "",
    val modbusHost: String = "",
    val modbusPort: Int = 8899,
    val unitId: Int = 1,
    val socRegister: Int = 184,
    val loadRegister: Int = 178,
    val gridRegister: Int = 150,
    val voltageRegister: Int = 183,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val cloudEnabled: Boolean = false,
    val cloudAppId: String = "",
    val cloudAppSecret: String = "",
    val cloudEmail: String = "",
    val cloudPassword: String = ""
) {
    fun json(): String = JSONObject()
        .put("mode", mode).put("endpoint", endpoint).put("method", method).put("headersJson", headersJson)
        .put("body", body).put("socPath", socPath).put("voltagePath", voltagePath).put("loadPath", loadPath)
        .put("gridPath", gridPath).put("lowSoc", lowSoc).put("overloadWatts", overloadWatts)
        .put("pollIntervalSeconds", pollIntervalSeconds).put("autoStart", autoStart).put("threePhase", threePhase)
        .put("inverterSerial", inverterSerial).put("loggerSerial", loggerSerial).put("modbusHost", modbusHost)
        .put("modbusPort", modbusPort).put("unitId", unitId).put("socRegister", socRegister)
        .put("loadRegister", loadRegister).put("gridRegister", gridRegister).put("voltageRegister", voltageRegister)
        .put("telegramToken", telegramToken).put("telegramChatId", telegramChatId)
        .put("cloudEnabled", cloudEnabled).put("cloudAppId", cloudAppId).put("cloudAppSecret", cloudAppSecret)
        .put("cloudEmail", cloudEmail).put("cloudPassword", cloudPassword).toString()

    companion object {
        fun parse(raw: String): AppConfig = runCatching {
            val j = JSONObject(raw)
            AppConfig(
                mode=j.optString("mode","DEMO"), endpoint=j.optString("endpoint"), method=j.optString("method","GET"),
                headersJson=j.optString("headersJson","{}"), body=j.optString("body"),
                socPath=j.optString("socPath","battery.soc"), voltagePath=j.optString("voltagePath","battery.voltage"),
                loadPath=j.optString("loadPath","inverter.load_power"), gridPath=j.optString("gridPath","grid.connected"),
                lowSoc=j.optDouble("lowSoc",30.0).toFloat(), overloadWatts=j.optInt("overloadWatts",3000),
                pollIntervalSeconds=j.optInt("pollIntervalSeconds",6).coerceIn(3,60),
                autoStart=j.optBoolean("autoStart",true), threePhase=j.optBoolean("threePhase",false),
                inverterSerial=j.optString("inverterSerial"), loggerSerial=j.optString("loggerSerial"),
                modbusHost=j.optString("modbusHost"), modbusPort=j.optInt("modbusPort",8899),
                unitId=j.optInt("unitId",1), socRegister=j.optInt("socRegister",184),
                loadRegister=j.optInt("loadRegister",178), gridRegister=j.optInt("gridRegister",150),
                voltageRegister=j.optInt("voltageRegister",183), telegramToken=j.optString("telegramToken"),
                telegramChatId=j.optString("telegramChatId"), cloudEnabled=j.optBoolean("cloudEnabled",false),
                cloudAppId=j.optString("cloudAppId"), cloudAppSecret=j.optString("cloudAppSecret"),
                cloudEmail=j.optString("cloudEmail"), cloudPassword=j.optString("cloudPassword")
            )
        }.getOrDefault(AppConfig())
    }
}

private object ConfigCipher {
    private const val alias = "powerhub-config-key"
    private const val prefix = "enc1:"
    private const val transformation = "AES/GCM/NoPadding"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return prefix + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(prefix)) return value
        val combined = Base64.decode(value.removePrefix(prefix), Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

object ConfigStore {
    private val key = stringPreferencesKey("config")
    suspend fun load(context: Context): AppConfig {
        val stored = context.powerHubStore.data.first()[key] ?: "{}"
        return runCatching { AppConfig.parse(ConfigCipher.decrypt(stored)) }.getOrDefault(AppConfig())
    }
    suspend fun save(context: Context, config: AppConfig) {
        val encrypted = ConfigCipher.encrypt(config.json())
        context.powerHubStore.edit { it[key] = encrypted }
    }
}
