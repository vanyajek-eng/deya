package com.powerhub.pulse

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class ChargeState(
    val owner: String="",val confirmed: Boolean?=null,val confirmedAt: Long=0,
    val pending: Boolean=false,val orderId: String?=null,val desired: Boolean?=null,
    val message: String="Режим ще не підтверджений"
) {
    fun json()=JSONObject().put("owner",owner).put("confirmed",confirmed ?: JSONObject.NULL).put("at",confirmedAt)
        .put("pending",pending).put("order",orderId ?: JSONObject.NULL).put("desired",desired ?: JSONObject.NULL).put("message",message)
    companion object {
        fun parse(j: JSONObject)=ChargeState(j.optString("owner"),if(j.isNull("confirmed")) null else j.getBoolean("confirmed"),
            j.optLong("at"),j.optBoolean("pending"),if(j.isNull("order")) null else j.optString("order"),
            if(j.isNull("desired")) null else j.getBoolean("desired"),j.optString("message"))
    }
}
object ChargingController {
    val state=MutableStateFlow(ChargeState())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var active: Job?=null
    fun owner(s: Settings)=listOf(s.baseUrl,s.appId,s.email,s.companyId,s.inverterSn.trim()).joinToString("|")
    private fun key(owner: String)=java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun prefs(c: Context)=c.getSharedPreferences("charging_commands",Context.MODE_PRIVATE)
    private fun save(c: Context,v: ChargeState) {
        prefs(c).edit().putString(key(v.owner),v.json().toString()).commit()
        if(state.value.owner==v.owner) state.value=v
    }
    fun load(c: Context,s: Settings) {
        val owner=owner(s)
        if(state.value.owner==owner) return
        state.value=runCatching {ChargeState.parse(JSONObject(prefs(c).getString(key(owner),"")!!))}.getOrDefault(ChargeState(owner))
        if(state.value.pending && state.value.orderId!=null && active?.isActive!=true) check(c,s)
    }
    fun request(c: Context,s: Settings,enabled: Boolean) {
        load(c,s)
        if(active?.isActive==true) return
        val previous=state.value
        val pending=previous.copy(pending=true,orderId=null,desired=enabled,message="Надсилання команди Deye…")
        save(c,pending)
        active=scope.launch {
            var acceptedState: ChargeState?=null
            try {
                s.validate()
                val api=DeyeApi()
                val id=api.setGridCharge(s,enabled)
                val accepted=pending.copy(orderId=id,message="Deye прийняв команду. Очікуємо виконання…")
                acceptedState=accepted
                save(c,accepted)
                Store.log(c,"Команда заряджання надіслана",if(enabled) "Увімкнути заряджання від мережі • $id" else "Вимкнути заряджання від мережі • $id")
                poll(c,s,api,accepted)
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {
                val known=acceptedState
                if(known!=null) save(c,known.copy(message="Команда надіслана, але виконання не підтверджене. Натисни «Перевірити виконання»."))
                else save(c,previous.copy(pending=false,desired=null,message=(e.message ?: "Помилка команди")+
                    " Результат не підтверджений. Автоматичного повторного надсилання немає."))
                Store.log(c,"Заряджання: виконання не підтверджене",e.message ?: "Перевір Deye Cloud")
            }
        }
    }
    fun check(c: Context,s: Settings) {
        if(active?.isActive==true) return
        val current=state.value
        if(current.owner!=owner(s) || current.orderId.isNullOrBlank()) return
        active=scope.launch {
            try {poll(c,s,DeyeApi(),current)}
            catch(e: Exception) {
                if(e is CancellationException) throw e
                save(c,current.copy(message="Не вдалося перевірити команду. "+(e.message ?: "")))
            }
        }
    }
    private suspend fun poll(c: Context,s: Settings,api: DeyeApi,current: ChargeState) {
        val id=current.orderId ?: return
        repeat(10) {
            val j=api.gridChargeOrder(s,id)
            when(j.optInt("status",-1)) {
                666 -> {
                    val updated=current.copy(confirmed=current.desired,confirmedAt=System.currentTimeMillis(),pending=false,
                        message="Deye підтвердив виконання команди")
                    save(c,updated)
                    Store.log(c,"Режим заряджання змінено",if(current.desired==true) "Заряджання від мережі дозволено." else "Заряджання від мережі вимкнено.")
                    return
                }
                400,500 -> {
                    save(c,current.copy(pending=false,message="Deye не виконав команду: "+j.optString("error").take(160)))
                    Store.log(c,"Deye відхилив команду заряджання",j.optString("error").take(160))
                    return
                }
            }
            delay(3000)
        }
        save(c,current.copy(pending=true,message="Команда ще обробляється. Натисни «Перевірити виконання»."))
    }
}
