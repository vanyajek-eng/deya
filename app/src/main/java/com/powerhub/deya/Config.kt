package com.powerhub.deya

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

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
    val lowSoc: Float = 20f,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val modbusHost: String = "",
    val modbusPort: Int = 502,
    val unitId: Int = 1,
    val socRegister: Int = 0,
    val loadRegister: Int = 0,
    val gridRegister: Int = 0
) {
    fun json(): String = JSONObject().put("mode", mode).put("endpoint", endpoint).put("method", method)
        .put("headersJson", headersJson).put("body", body).put("socPath", socPath)
        .put("voltagePath", voltagePath).put("loadPath", loadPath).put("gridPath", gridPath)
        .put("lowSoc", lowSoc).put("telegramToken", telegramToken).put("telegramChatId", telegramChatId)
        .put("modbusHost", modbusHost).put("modbusPort", modbusPort).put("unitId", unitId)
        .put("socRegister", socRegister).put("loadRegister", loadRegister).put("gridRegister", gridRegister).toString(2)

    companion object {
        fun parse(raw: String): AppConfig = runCatching {
            val j = JSONObject(raw)
            AppConfig(
                mode=j.optString("mode","DEMO"), endpoint=j.optString("endpoint"), method=j.optString("method","GET"),
                headersJson=j.optString("headersJson","{}"), body=j.optString("body"),
                socPath=j.optString("socPath","battery.soc"), voltagePath=j.optString("voltagePath","battery.voltage"),
                loadPath=j.optString("loadPath","inverter.load_power"), gridPath=j.optString("gridPath","grid.connected"),
                lowSoc=j.optDouble("lowSoc",20.0).toFloat(), telegramToken=j.optString("telegramToken"),
                telegramChatId=j.optString("telegramChatId"), modbusHost=j.optString("modbusHost"),
                modbusPort=j.optInt("modbusPort",502), unitId=j.optInt("unitId",1),
                socRegister=j.optInt("socRegister",0), loadRegister=j.optInt("loadRegister",0), gridRegister=j.optInt("gridRegister",0)
            )
        }.getOrDefault(AppConfig())
    }
}

object ConfigStore {
    private val key = stringPreferencesKey("config")
    suspend fun load(context: Context) = AppConfig.parse(context.powerHubStore.data.first()[key] ?: "{}")
    suspend fun save(context: Context, config: AppConfig) { context.powerHubStore.edit { it[key] = config.json() } }
}
