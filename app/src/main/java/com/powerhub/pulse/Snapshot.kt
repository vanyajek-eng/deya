package com.powerhub.pulse

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MeasurePoint(val key: String, val value: String?, val unit: String = "") {
    val number: Double? get() = value?.trim()?.replace(',','.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
    fun display(): String = when {
        value.isNullOrBlank() || value=="null" -> "Немає даних"
        unit.isBlank() -> value
        else -> "$value $unit"
    }
    fun json()=JSONObject().put("key",key).put("value",value ?: JSONObject.NULL).put("unit",unit)
}
data class Metrics(
    val soc: Float?, val volts: Float?, val loadWatts: Float?, val grid: Boolean?,
    val pvWatts: Float?, val batteryWatts: Float?, val gridWatts: Float?
)
data class Snapshot(
    val sn: String, val measuredAt: Long, val deviceState: Int, val source: String,
    val points: List<MeasurePoint>, val metadata: Map<String,String> = emptyMap(),
    val mapping: Settings = Settings()
) {
    val online: Boolean get() = deviceState in listOf(1,2)
    fun fresh(now: Long,maxAgeSeconds: Int): Boolean =
        online && measuredAt>0 && now-measuredAt<=maxAgeSeconds*1000L && measuredAt-now<=60000L
    fun find(key: String,vararg aliases: String): MeasurePoint? {
        val byKey=points.associateBy { normalize(it.key) }
        return (listOf(key)+aliases.toList()).firstNotNullOfOrNull { byKey[normalize(it)]?.takeIf { p -> p.value!=null } }
    }
    fun watts(key: String,vararg aliases: String): Float? {
        val p=find(key,*aliases) ?: return null
        val v=p.number?.toFloat() ?: return null
        return when(p.unit.trim().lowercase(Locale.ROOT)) {
            "kw","квт" -> v*1000f
            "w","вт" -> v
            "" -> if(normalize(key) in listOf(normalize(mapping.loadKey),"totalconsumptionpower","loadpower","consumptionpower")) {
                if(mapping.cloudLoadUnit=="kW") v*1000f else v
            } else null
            else -> null
        }?.takeIf { it.isFinite() }
    }
    fun metrics(): Metrics {
        fun number(key: String,vararg aliases: String)=find(key,*aliases)?.number?.toFloat()
        val gridPoint=if(mapping.gridIsVoltage)
            find(mapping.gridKey,"GridVoltageL1L2","GridVoltage","GridVoltageL1","GridVoltageR","GridVoltageL2","GridVoltageS","GridVoltageL3","GridVoltageT")
            else find(mapping.gridKey)
        val grid=if(mapping.gridIsVoltage) gridPoint?.number?.let { voltage->
            when(gridPoint.unit.trim().lowercase(Locale.ROOT)) {
                "v","в","" -> voltage>=80.0
                "kv","кв" -> voltage*1000>=80.0
                else -> null
            }
        } else when(gridPoint?.value?.lowercase(Locale.ROOT)) {
            "1","true","on" -> true
            "0","false","off" -> false
            else -> null
        }
        return Metrics(
            number(mapping.socKey,"SOC","BatterySOC")?.takeIf { it in 0f..100f },
            number(mapping.voltageKey,"BatteryVoltage","DCVoltageBattery")?.takeIf { it>0f && it<1000f },
            watts(mapping.loadKey,"TotalConsumptionPower","ConsumptionPower","LoadPower")?.takeIf { it>=0f },
            grid,
            watts("TotalDCInputPower","TotalPVPower","PVPower"),
            watts("BatteryPower"),
            watts("TotalGridPower","GridTiePower")
        )
    }
    fun requireReading(): Reading {
        check(online) { "Логер офлайн" }
        val m=metrics()
        return Reading(m.soc ?: error("Немає заряду АКБ"),m.volts ?: error("Немає напруги АКБ"),
            m.loadWatts ?: error("Немає навантаження"),m.grid ?: error("Немає стану мережі"),measuredAt,source)
    }
    fun json(): JSONObject {
        val a=JSONArray();points.forEach { a.put(it.json()) }
        val meta=JSONObject();metadata.forEach { (k,v)->meta.put(k,v) }
        return JSONObject().put("sn",sn).put("at",measuredAt).put("state",deviceState)
            .put("source",source).put("points",a).put("meta",meta)
        // Credentials and Settings are deliberately never persisted with telemetry.
    }
    companion object {
        fun normalize(k: String)=k.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        fun parse(j: JSONObject,mapping: Settings=Settings()): Snapshot {
            val a=j.getJSONArray("points")
            val meta=j.optJSONObject("meta") ?: JSONObject()
            return Snapshot(j.optString("sn"),j.getLong("at"),j.optInt("state",1),j.optString("source","Deye Cloud"),
                List(a.length()) { a.getJSONObject(it).let { p ->
                    MeasurePoint(p.getString("key"),if(p.isNull("value")) null else p.optString("value"),p.optString("unit"))
                } },meta.keys().asSequence().associateWith { meta.optString(it) },mapping)
        }
        fun from(r: Reading)=Snapshot("",r.measuredAt,1,r.source,listOf(
            MeasurePoint("SOC",r.soc.toString(),"%"),MeasurePoint("BatteryVoltage",r.batteryVoltage.toString(),"V"),
            MeasurePoint("TotalConsumptionPower",r.loadKw.times(1000).toString(),"W"),
            MeasurePoint("GridPresent",r.gridPresent.toString())
        )).copy(mapping=Settings(gridKey="GridPresent",gridIsVoltage=false))
    }
}
data class HistorySample(val at: Long,val values: Map<String,Double>,val units: Map<String,String> = emptyMap()) {
    fun json(): JSONObject {
        val v=JSONObject();values.forEach { (k,n)->v.put(k,n) }
        val u=JSONObject();units.forEach { (k,unit)->u.put(k,unit) }
        return JSONObject().put("at",at).put("values",v).put("units",u)
    }
    companion object {
        fun from(s: Snapshot)=HistorySample(s.measuredAt,s.points.mapNotNull { p->p.number?.let { p.key to it } }.toMap(),s.points.associate {it.key to it.unit})
        fun parse(j: JSONObject): HistorySample {
            val v=j.getJSONObject("values")
            val u=j.optJSONObject("units") ?: JSONObject()
            return HistorySample(j.getLong("at"),v.keys().asSequence().associateWith { v.getDouble(it) },u.keys().asSequence().associateWith {u.getString(it)})
        }
    }
}
object TelemetryCatalog {
    val groups=listOf("Усі","Акумулятор","Мережа","Споживання","Сонце","Температура","Система","Інше")
    fun group(key: String): String {
        val k=Snapshot.normalize(key)
        return when {
            k.contains("temperature") || k.contains("temp") -> "Температура"
            k.contains("battery") || k.startsWith("bms") || k=="soc" || k.contains("charge") || k.contains("discharge") -> "Акумулятор"
            k.contains("grid") || k.contains("purchased") || k.contains("externalct") -> "Мережа"
            k.contains("consumption") || k.startsWith("load") -> "Споживання"
            k.contains("pv") || k.contains("dcinput") -> "Сонце"
            k.contains("version") || k.contains("rated") || k.contains("protocol") || k in listOf("main","hmi") -> "Система"
            else -> "Інше"
        }
    }
    private val names=mapOf(
        "SOC" to "Заряд акумулятора","BatteryVoltage" to "Напруга акумулятора","BatteryCurrent" to "Струм акумулятора",
        "BatteryPower" to "Потужність акумулятора","BatteryRatedCapacity" to "Номінальна ємність АКБ",
        "TotalConsumptionPower" to "Споживання будинку","DailyConsumption" to "Споживання за день",
        "CumulativeConsumption" to "Загальне споживання","TotalDCInputPower" to "Сумарна потужність сонця",
        "GridVoltageL1L2" to "Напруга зовнішньої мережі","GridVoltage" to "Напруга мережі",
        "GridCurrentL1L2" to "Струм мережі","TotalGridPower" to "Потужність мережі","GridFrequency" to "Частота мережі",
        "GridTiePower" to "Потужність підключення до мережі","ExternalCTPowerL1L2" to "Потужність зовнішнього CT",
        "LoadVoltageL1L2" to "Напруга навантаження","InverterOutputPowerL1L2" to "Вихідна потужність інвертора",
        "ACVoltageRUA" to "Вихідна напруга AC","ACCurrentRUA" to "Вихідний струм AC",
        "ACOutputFrequencyR" to "Вихідна частота AC","TotalActiveProduction" to "Загальний виробіток",
        "DailyActiveProduction" to "Виробіток за день","PV Daily Power Generation Active" to "Сонячний виробіток за день",
        "PVDailyPowerGenerationActive" to "Сонячний виробіток за день",
        "PVCumulativePowerGenerationActive" to "Загальний сонячний виробіток",
        "TotalChargeEnergy" to "Загальна енергія заряджання","TotalDischargeEnergy" to "Загальна енергія розряджання",
        "DailyChargingEnergy" to "Заряджання за день","DailyDischargingEnergy" to "Розряджання за день",
        "DailyEnergyPurchased" to "Куплено з мережі за день","CumulativeEnergyPurchased" to "Загалом куплено з мережі",
        "DailyGridFeedIn" to "Віддано в мережу за день","CumulativeGridFeedIn" to "Загалом віддано в мережу",
        "BMSVoltage" to "Напруга BMS","BMSCurrent" to "Струм BMS","BMSTemperature" to "Температура BMS",
        "BMSSOC" to "Заряд за BMS","BMSSOH" to "Стан здоров’я АКБ",
        "BMSChargeVoltage" to "Напруга заряджання BMS","BMSDischargeVoltage" to "Напруга розряджання BMS",
        "ChargeCurrentLimit" to "Обмеження струму заряджання","DischargeCurrentLimit" to "Обмеження струму розряджання",
        "Temperature-Battery" to "Температура акумулятора","DC Temperature" to "Температура DC",
        "AC Temperature" to "Температура AC","RatedPower" to "Номінальна потужність",
        "ProtocolVersion" to "Версія протоколу","LithiumBatteryVersionNumber" to "Версія літієвої АКБ",
        "MAIN" to "Основна прошивка","HMI" to "Прошивка дисплея"
    ).mapKeys { Snapshot.normalize(it.key) }
    fun title(key: String): String {
        names[Snapshot.normalize(key)]?.let { return it }
        Regex("^(DCVoltage|DCCurrent|DCPower)PV(\\d+)$").matchEntire(key)?.let {
            val type=when(it.groupValues[1]) { "DCVoltage"->"Напруга";"DCCurrent"->"Струм";else->"Потужність" }
            return "$type PV ${it.groupValues[2]}"
        }
        return key.replace(Regex("(?<=[a-z])(?=[A-Z])")," ").replace('_',' ')
    }
    fun filtered(points: List<MeasurePoint>,query: String,group: String,favorites: Set<String>,onlyFavorites: Boolean)=
        points.filter { p->(group=="Усі" || TelemetryCatalog.group(p.key)==group) && (!onlyFavorites || p.key in favorites) &&
            (query.isBlank() || (p.key+" "+title(p.key)+" "+p.unit+" "+p.value).contains(query,true)) }
            .sortedWith(compareBy<MeasurePoint> { it.key !in favorites }.thenBy { title(it.key) })
}
