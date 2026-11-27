package com.powerhub.pulse

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramFailure(
    message: String,val retryable: Boolean,val retryAfterSeconds: Int=0
): IOException(message)
object TelegramTransport {
    private val client=OkHttpClient.Builder().callTimeout(15,TimeUnit.SECONDS).followRedirects(false).build()
    fun result(code: Int,raw: String) {
        val j=runCatching {JSONObject(raw)}.getOrNull()
        if(code in 200..299 && j?.optBoolean("ok")==true) return
        val error=j?.optInt("error_code",code) ?: code
        val description=j?.optString("description")?.take(250).orEmpty()
        val hint=when(error) {
            400 -> "Перевір Chat ID і надішли /start боту."
            401,404 -> "Перевір bot token."
            403 -> "Розблокуй бота та надішли /start."
            429 -> "Telegram обмежив частоту. Повторимо пізніше."
            else -> "Спробуємо доставити пізніше."
        }
        throw TelegramFailure("Telegram $error: $description $hint",
            error==429 || error>=500 || j==null || (code in 200..299 && error==code),
            j?.optJSONObject("parameters")?.optInt("retry_after",0) ?: 0)
    }
    fun send(token: String,chat: String,text: String) {
        val trimmed=token.trim()
        require(Regex("[0-9]+:[A-Za-z0-9_-]+").matches(trimmed)) {"Некоректний формат bot token"}
        require(chat.trim().isNotEmpty()) {"Вкажи Chat ID"}
        val body=FormBody.Builder().add("chat_id",chat.trim()).add("text",text).build()
        val req=Request.Builder().url("https://api.telegram.org/bot$trimmed/sendMessage").post(body).build()
        client.newCall(req).execute().use {response->result(response.code,response.body?.string().orEmpty())}
    }
}
