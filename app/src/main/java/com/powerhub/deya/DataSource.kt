package com.powerhub.deya

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DataSource {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun read(config: AppConfig): Telemetry = when (config.mode) {
        "SOLARMAN_V5" -> SolarmanV5Client(config.modbusHost, config.loggerSerial, config.modbusPort, config.unitId).readTelemetry(config)
        "HTTP_JSON" -> readHttp(config)
        else -> error("Обери локальне підключення Solarman або HTTP JSON.")
    }

    private fun readHttp(config: AppConfig): Telemetry {
        
        require(config.endpoint.startsWith("http://") || config.endpoint.startsWith("https://")) { "Вкажи коректний HTTP/HTTPS URL" }
        val builder = Request.Builder().url(config.endpoint)
        val headers = JSONObject(config.headersJson)
        headers.keys().forEach { key -> builder.addHeader(key, headers.getString(key)) }
        val request = when (config.method.uppercase()) {
            "POST" -> builder.post(config.body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            else -> builder.get().build()
        }
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            return Telemetry(
                valueAt(json, config.socPath).toFloat(),
                valueAt(json, config.voltagePath).toFloat(),
                valueAt(json, config.loadPath).toFloat(),
                valueAt(json, config.gridPath).let { it == "true" || it == "1" || it == "on" },
                "HTTP_JSON", raw
            )
        }
    }

    private fun valueAt(root: JSONObject, path: String): String {
        var current: Any = root
        path.split(".").filter { it.isNotBlank() }.forEach { key ->
            current = (current as? JSONObject)?.get(key) ?: error("JSON path not found: $path")
        }
        return current.toString()
    }
}
