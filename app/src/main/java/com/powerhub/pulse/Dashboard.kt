package com.powerhub.pulse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Dashboard(state: ScreenState,s: Settings,now: Long,onParameters: ()->Unit,onWidget: ()->Unit,onSetup: ()->Unit,onMonitor: ()->Unit) {
    val snapshot=state.snapshot
    val m=snapshot?.metrics()
    val current=snapshot?.fresh(now,s.maxAgeSeconds)==true && state.lastError==null
    val gridText=when {
        snapshot==null -> "Очікуємо підключення"
        !current -> "Останні отримані дані"
        m?.grid==true -> "Зовнішня мережа є"
        m?.grid==false -> "Працюємо від акумулятора"
        else -> "Стан мережі недоступний"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("Енергія твого дому",if(snapshot==null) "Підключи Deye Cloud, щоб почати" else "Показники, резерв та споживання")
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=(if(current) Mint else Gold).copy(alpha=.1f),shape=RoundedCornerShape(30.dp)) {
                Text("●  $gridText",modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp),
                    fontSize=11.sp,color=if(current) Mint else Gold)
            }
            if(state.running) Text("МОНІТОРИНГ",fontSize=9.sp,color=Subtle,letterSpacing=1.sp)
        }
        Surface(shape=RoundedCornerShape(28.dp),color=Color.Transparent) {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF193A3B),Color(0xFF17283D),Color(0xFF252344)))).padding(22.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    SmallLabel("АКУМУЛЯТОР",Mint)
                    Icon(Icons.Rounded.BatteryChargingFull,null,tint=Mint,modifier=Modifier.size(22.dp))
                }
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(m?.soc?.let {"${it.toInt()}%"} ?: "—",fontSize=68.sp,lineHeight=72.sp,fontWeight=FontWeight.Light,letterSpacing=(-3).sp,color=Color.White)
                    Column(Modifier.padding(bottom=10.dp)) {
                        Text("доступний заряд",fontSize=13.sp,color=Color.White.copy(alpha=.6f))
                        Text(if(current) "за останнім виміром" else "останнє відоме значення",fontSize=10.sp,color=Color.White.copy(alpha=.45f))
                    }
                }
                LinearProgressIndicator(progress={(m?.soc ?: 0f)/100f},modifier=Modifier.fillMaxWidth().height(5.dp),
                    color=if(m?.soc!=null && m.soc<=s.lowSoc) Gold else Mint,trackColor=Color.White.copy(alpha=.12f))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Stat("НАПРУГА",m?.volts?.let {"%.1f В".format(it)} ?: "—")
                    Stat("ПОТУЖНІСТЬ",powerLabel(m?.batteryWatts))
                    Stat("ПОРІГ СИГНАЛУ","${s.lowSoc}%")
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            PowerTile("Споживання",powerLabel(m?.loadWatts),Icons.Rounded.Home,Gold,Modifier.weight(1f))
            PowerTile("Сонце",powerLabel(m?.pvWatts),Icons.Rounded.WbSunny,Mint,Modifier.weight(1f))
        }
        PulseCard {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Енергосистема",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                SmallLabel("ПОТУЖНІСТЬ")
            }
            EnergySystem(m)
            Text("Показано значення API. Знак потужності мережі та АКБ збережено від Deye.",color=Subtle,fontSize=10.sp,lineHeight=14.sp)
        }
        PulseCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(if(current) Icons.Rounded.CloudDone else Icons.Rounded.CloudQueue,null,tint=if(current) Mint else Gold,modifier=Modifier.size(18.dp))
                Text(if(snapshot==null) "Дані ще не отримано" else if(current) "З’єднання з Deye" else "Актуальність даних",fontWeight=FontWeight.SemiBold,fontSize=14.sp)
            }
            Text(state.status,color=if(state.lastError!=null) Gold else Subtle,fontSize=12.sp)
            snapshot?.let {
                Text("Вимір: ${timeLabel(it.measuredAt)}",color=Subtle,fontSize=11.sp)
                val age=if(it.measuredAt>0) ((now-it.measuredAt)/1000).coerceAtLeast(0) else null
                Text(if(age==null) "Deye не надав час виміру" else "Вік: ${age/60} хв ${age%60} с • ${it.points.count { p->p.value!=null }} значень",
                    color=Subtle,fontSize=11.sp)
                if(!current) Text("Це останній відомий стан. Поточна наявність світла не підтверджена.",color=Gold,fontSize=11.sp)
            }
            if(snapshot==null) Button(onClick=onSetup,modifier=Modifier.fillMaxWidth()) {Text("Підключити Deye Cloud")}
        }
        snapshot?.let { data->
            val keys=listOf("DailyConsumption","DailyChargingEnergy","DailyDischargingEnergy","DailyEnergyPurchased","PVDailyPowerGenerationActive","DailyGridFeedIn")
            val daily=keys.mapNotNull { key->data.find(key) }.filter { it.value!=null }
            if(daily.isNotEmpty()) PulseCard {
                Text("Сьогодні",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                daily.forEach { p->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(TelemetryCatalog.title(p.key),color=Subtle,fontSize=12.sp,modifier=Modifier.weight(1f))
                        Text(p.display(),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    }
                }
            }
        }
        OutlinedButton(onClick=onParameters,modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(Icons.Rounded.GridView,null,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
            Text("Усі параметри Deye")
        }
        PulseCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Widgets,null,tint=Violet,modifier=Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text("Енергія на головному екрані",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                    Text("Показники та оновлення без відкриття додатку",color=Subtle,fontSize=11.sp)
                }
            }
            Button(onClick=onWidget,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Violet,contentColor=Night)) {
                Text("Додати готовий віджет")
            }
        }
        Button(onClick=onMonitor,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)) {
            Icon(if(state.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)
            Spacer(Modifier.width(8.dp));Text(if(state.running) "Призупинити постійний моніторинг" else "Увімкнути постійний моніторинг",fontSize=12.sp)
        }
        Text("Хмара оновлюється з інтервалом логера. Старі виміри не використовуються для нових сигналів.",color=Subtle,fontSize=10.sp,lineHeight=15.sp)
        Spacer(Modifier.height(18.dp))
    }
}
@Composable private fun Stat(label: String,value: String) {
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        SmallLabel(label,Color.White.copy(alpha=.45f))
        Text(value,fontSize=16.sp,fontWeight=FontWeight.Medium)
    }
}
@Composable private fun PowerTile(title: String,value: String,icon: androidx.compose.ui.graphics.vector.ImageVector,color: Color,modifier: Modifier) {
    PulseCard(modifier) {
        Icon(icon,null,tint=color,modifier=Modifier.size(22.dp))
        Text(title,fontSize=11.sp,color=Subtle)
        Text(value,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=color,maxLines=1)
    }
}
@Composable private fun EnergySystem(m: Metrics?) {
    Box(Modifier.fillMaxWidth().height(244.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center=Offset(size.width/2,size.height/2)
            listOf(Offset(size.width/2,42.dp.toPx()),Offset(55.dp.toPx(),size.height/2),
                Offset(size.width-55.dp.toPx(),size.height/2),Offset(size.width/2,size.height-42.dp.toPx())).forEach {
                drawLine(Edge,center,it,2.dp.toPx(),StrokeCap.Round)
            }
            drawCircle(Mint.copy(alpha=.06f),42.dp.toPx(),center)
        }
        Column(Modifier.align(Alignment.TopCenter),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.WbSunny,null,tint=Mint,modifier=Modifier.size(20.dp))
            Text("Сонце",color=Subtle,fontSize=10.sp);Text(powerLabel(m?.pvWatts),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        Column(Modifier.align(Alignment.CenterStart),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ElectricalServices,null,tint=Violet,modifier=Modifier.size(20.dp))
            Text("Мережа",color=Subtle,fontSize=10.sp);Text(powerLabel(m?.gridWatts),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        Column(Modifier.align(Alignment.CenterEnd),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Home,null,tint=Gold,modifier=Modifier.size(20.dp))
            Text("Будинок",color=Subtle,fontSize=10.sp);Text(powerLabel(m?.loadWatts),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        Column(Modifier.align(Alignment.BottomCenter),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.BatteryChargingFull,null,tint=Mint,modifier=Modifier.size(20.dp))
            Text("Акумулятор",color=Subtle,fontSize=10.sp);Text(powerLabel(m?.batteryWatts),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        Surface(Modifier.align(Alignment.Center).size(72.dp),color=Color(0xFF1D3440),shape=RoundedCornerShape(24.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Icon(Icons.Rounded.Bolt,null,tint=Mint,modifier=Modifier.size(28.dp))
                Text("DEYE",fontSize=10.sp,fontWeight=FontWeight.Black,letterSpacing=1.sp)
            }
        }
    }
}
