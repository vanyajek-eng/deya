package com.powerhub.deya

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Telegram {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    fun send(config: AppConfig, text: String) {
        require(config.telegramToken.isNotBlank() && config.telegramChatId.isNotBlank()) { "Telegram bot token або chat ID не задані" }
        val body = FormBody.Builder().add("chat_id", config.telegramChatId).add("text", text).build()
        val request = Request.Builder().url("https://api.telegram.org/bot${config.telegramToken}/sendMessage").post(body).build()
        client.newCall(request).execute().use { check(it.isSuccessful) { "Telegram HTTP ${it.code}" } }
    }
}
