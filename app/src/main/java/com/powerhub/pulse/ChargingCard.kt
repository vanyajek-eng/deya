package com.powerhub.pulse

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun ChargingCard(s: Settings) {
    val c=androidx.compose.ui.platform.LocalContext.current
    val state by ChargingController.state.collectAsStateWithLifecycle()
    LaunchedEffect(ChargingController.owner(s)) {ChargingController.load(c,s)}
    PulseCard {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.BatteryChargingFull,null,tint=Mint)
            Column(Modifier.weight(1f)) {
                Text("Заряджання АКБ від мережі",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                Text("Дозвіл Grid Charge",fontSize=11.sp,color=Subtle)
            }
            if(state.confirmed!=null) Switch(checked=state.confirmed==true,
                onCheckedChange={ChargingController.request(c,s,it)},enabled=!state.pending && s.source=="CLOUD")
        }
        if(state.confirmed==null && !state.pending) {
            Text("Поточний дозвіл не прочитано з інвертора. Вибери потрібну команду.",color=Subtle,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick={ChargingController.request(c,s,true)},enabled=s.source=="CLOUD",modifier=Modifier.weight(1f)) {Text("Увімкнути")}
                OutlinedButton(onClick={ChargingController.request(c,s,false)},enabled=s.source=="CLOUD",modifier=Modifier.weight(1f)) {Text("Вимкнути")}
            }
        }
        if(state.pending) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        Text(state.message,color=if(state.pending) Gold else Subtle,fontSize=12.sp)
        if(state.confirmedAt>0) Text("Остання підтверджена команда: "+timeLabel(state.confirmedAt)+
            ". Це не показник поточного струму заряджання.",color=Subtle,fontSize=10.sp)
        if(state.orderId!=null) OutlinedButton(onClick={ChargingController.check(c,s)},modifier=Modifier.fillMaxWidth()) {Text("Перевірити виконання")}
        Text("Перемикач змінює лише дозвіл заряджання від зовнішньої мережі. Виконання залежить від прав API, прошивки та налаштувань Deye.",color=Subtle,fontSize=11.sp)
    }
}
