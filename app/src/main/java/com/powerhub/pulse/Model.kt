package com.powerhub.pulse

import org.json.JSONObject

data class Reading(
    val soc: Float, val volts: Float, val loadWatts: Float,
    val grid: Boolean, val measuredAt: Long, val source: String
) {
    fun validate(now: Long, maxAgeMs: Long): Reading {
        require(soc.isFinite() && soc in 0f..100f) { "Некоректний заряд АКБ" }
        require(volts.isFinite() && volts > 0f && volts < 1000f) { "Некоректна напруга АКБ" }
        require(loadWatts.isFinite() && loadWatts in 0f..1000000f) { "Некоректна потужність" }
        require(measuredAt > 0 && now - measuredAt <= maxAgeMs && measuredAt - now <= 60000L) {
            "Застарілі дані інвертора. Перевір логер та інтернет."
        }
        return this
    }
    fun json() = JSONObject().put("soc",soc).put("volts",volts).put("load",loadWatts)
        .put("grid",grid).put("at",measuredAt).put("source",source)
    companion object {
        fun parse(j: JSONObject) = Reading(j.getDouble("soc").toFloat(),j.getDouble("volts").toFloat(),
            j.getDouble("load").toFloat(),j.getBoolean("grid"),j.getLong("at"),j.getString("source"))
    }
}
data class Settings(
    val source: String = "CLOUD",
    val baseUrl: String = "https://eu1-developer.deyecloud.com",
    val appId: String = "", val secret: String = "", val email: String = "", val password: String = "",
    val inverterSn: String = "",
    val jsonUrl: String = "", val jsonHeaders: String = "{}",
    val socKey: String = "SOC", val voltageKey: String = "BatteryVoltage",
    val loadKey: String = "TotalConsumptionPower", val gridKey: String = "GridVoltageL1L2",
    val cloudLoadUnit: String = "W", val gridIsVoltage: Boolean = true,
    val lowSoc: Int = 20, val highWatts: Int = 3000,
    val pollSeconds: Int = 15, val confirmSeconds: Int = 30, val maxAgeSeconds: Int = 900,
    val telegramToken: String = "", val telegramChat: String = "", val boot: Boolean = true
) {
    fun validate() {
        require(lowSoc in 1..99 && highWatts in 100..1000000) { "Перевір пороги сповіщень" }
        require(pollSeconds in 3..300 && confirmSeconds in 0..300 && maxAgeSeconds in 30..3600) { "Перевір інтервали" }
        if (source == "CLOUD") {
            require(listOf(appId,secret,email,password,inverterSn).all { it.isNotBlank() }) { "Заповни дані Deye Cloud та SN інвертора" }
            require(baseUrl in listOf("https://eu1-developer.deyecloud.com","https://us1-developer.deyecloud.com","https://india-developer.deyecloud.com")) { "Обери сервер Deye Cloud" }
        } else {
            require(jsonUrl.startsWith("https://") || jsonUrl.startsWith("http://")) { "Вкажи URL локального JSON API" }
            JSONObject(jsonHeaders)
        }
    }
    fun json() = JSONObject().apply {
        put("source",source); put("baseUrl",baseUrl); put("appId",appId); put("secret",secret)
        put("email",email); put("password",password); put("inverterSn",inverterSn)
        put("jsonUrl",jsonUrl); put("jsonHeaders",jsonHeaders)
        put("socKey",socKey); put("voltageKey",voltageKey); put("loadKey",loadKey); put("gridKey",gridKey)
        put("cloudLoadUnit",cloudLoadUnit); put("gridIsVoltage",gridIsVoltage)
        put("lowSoc",lowSoc); put("highWatts",highWatts); put("pollSeconds",pollSeconds)
        put("confirmSeconds",confirmSeconds); put("maxAgeSeconds",maxAgeSeconds)
        put("telegramToken",telegramToken); put("telegramChat",telegramChat); put("boot",boot)
    }
    companion object {
        fun parse(j: JSONObject) = Settings(
            source=j.optString("source","CLOUD"),baseUrl=j.optString("baseUrl","https://eu1-developer.deyecloud.com"),
            appId=j.optString("appId"),secret=j.optString("secret"),email=j.optString("email"),
            password=j.optString("password"),inverterSn=j.optString("inverterSn"),jsonUrl=j.optString("jsonUrl"),
            jsonHeaders=j.optString("jsonHeaders","{}"),socKey=j.optString("socKey","SOC"),
            voltageKey=j.optString("voltageKey","BatteryVoltage"),loadKey=j.optString("loadKey","TotalConsumptionPower"),
            gridKey=j.optString("gridKey","GridVoltageL1L2"),cloudLoadUnit=j.optString("cloudLoadUnit","W"),
            gridIsVoltage=j.optBoolean("gridIsVoltage",true),lowSoc=j.optInt("lowSoc",20),
            highWatts=j.optInt("highWatts",3000),pollSeconds=j.optInt("pollSeconds",15),
            confirmSeconds=j.optInt("confirmSeconds",30),maxAgeSeconds=j.optInt("maxAgeSeconds",900),
            telegramToken=j.optString("telegramToken"),telegramChat=j.optString("telegramChat"),boot=j.optBoolean("boot",true)
        )
    }
}
data class Alarm(val kind: String, val title: String, val detail: String)
class AlarmEngine(saved: JSONObject = JSONObject()) {
    private var stable: Boolean? = if(saved.has("stable")) saved.getBoolean("stable") else null
    private var candidate: Boolean? = null
    private var since = 0L
    private var lastObserved = 0L
    private var low = saved.optBoolean("low",false)
    private var high = saved.optBoolean("high",false)
    fun gap() { candidate=null; since=0; lastObserved=0 }
    fun state() = JSONObject().apply { stable?.let { put("stable",it) }; put("low",low); put("high",high) }
    fun accept(r: Reading,s: Settings,now: Long): List<Alarm> =
        evaluate(r.soc,r.loadWatts,r.grid,r.measuredAt,s,now)
    fun acceptSnapshot(snapshot: Snapshot,s: Settings,now: Long): List<Alarm> {
        if(!snapshot.fresh(System.currentTimeMillis(),s.maxAgeSeconds)) { gap();return emptyList() }
        val m=snapshot.metrics()
        return evaluate(m.soc,m.loadWatts,m.grid,snapshot.measuredAt,s,now)
    }
    private fun evaluate(soc: Float?,watts: Float?,grid: Boolean?,at: Long,s: Settings,now: Long): List<Alarm> {
        val events=mutableListOf<Alarm>()
        if(lastObserved!=0L && now-lastObserved>s.pollSeconds*3000L+30000L) gap()
        lastObserved=now
        if(grid==null) { candidate=null;since=0 }
        else {
            if(stable==null && grid) stable=true
            if(stable!=grid) {
                if(candidate!=grid) { candidate=grid;since=at }
                if(at-since>=s.confirmSeconds*1000L) {
                    stable=grid;candidate=null
                    events+=Alarm("grid",if(grid) "Світло повернулося" else "Зовнішня мережа відсутня",
                        if(grid) "Інвертор знову бачить мережу." else "Живлення від акумуляторів. Перевір навантаження.")
                }
            } else { candidate=null;since=0 }
        }
        if(soc!=null) {
            if(soc>s.lowSoc+2) low=false
            if(soc<=s.lowSoc && !low) {
                low=true;events+=Alarm("battery","Низький заряд АКБ","Залишилося ${soc.toInt()}%. Поріг: ${s.lowSoc}%.")
            }
        }
        if(grid==true || (watts!=null && watts<s.highWatts-200)) high=false
        if(grid==false && watts!=null && watts>=s.highWatts && !high) {
            high=true;events+=Alarm("load","Високе споживання від АКБ","Навантаження ${watts.toInt()} Вт. Ліміт: ${s.highWatts} Вт.")
        }
        return events
    }
}
