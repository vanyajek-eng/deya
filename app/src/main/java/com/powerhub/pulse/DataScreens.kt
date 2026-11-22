package com.powerhub.pulse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun ParametersScreen(snapshot: Snapshot?,favorites: Set<String>,onFavorite: (String)->Unit) {
    var query by remember {mutableStateOf("")}
    var group by remember {mutableStateOf("Усі")}
    var onlyFavorites by remember {mutableStateOf(false)}
    var detail by remember {mutableStateOf<MeasurePoint?>(null)}
    val points=snapshot?.points.orEmpty()
    val shown=remember(points,query,group,favorites,onlyFavorites) {TelemetryCatalog.filtered(points,query,group,favorites,onlyFavorites)}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionTitle("Усі параметри","${points.count { it.value!=null }} значень • ${points.size} доступних полів API")
        OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("Назва, ключ або одиниця",fontSize=13.sp)},
            leadingIcon={Icon(Icons.Rounded.Search,null,tint=Subtle)},singleLine=true,
            modifier=Modifier.fillMaxWidth().testTag("parameter_search"),shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=onlyFavorites,onClick={onlyFavorites=!onlyFavorites},label={Text("★ Обрані")})
            TelemetryCatalog.groups.filter { it=="Усі" || points.any { p->TelemetryCatalog.group(p.key)==it } }.forEach {
                FilterChip(selected=group==it,onClick={group=it},label={Text(it)})
            }
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(snapshot==null) item {
                PulseCard {Text("Підключи інвертор у вкладці «Ще».",color=Subtle)}
            }
            if(snapshot!=null && shown.isEmpty()) item {PulseCard {Text("За цим запитом нічого не знайдено.",color=Subtle)}}
            items(shown,key={it.key}) { p->
                PulseCard(Modifier.clickable {detail=p}) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            SmallLabel(TelemetryCatalog.group(p.key).uppercase())
                            Text(TelemetryCatalog.title(p.key),fontSize=14.sp,fontWeight=FontWeight.Medium)
                            Text(p.key,color=Subtle,fontSize=10.sp)
                        }
                        IconButton(onClick={onFavorite(p.key)}) {Icon(if(p.key in favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if(p.key in favorites) "Прибрати з обраних" else "Додати в обрані",tint=if(p.key in favorites) Gold else Subtle,modifier=Modifier.size(20.dp))}
                    }
                    Text(p.display(),fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=if(p.value==null) Subtle else Mint)
                }
            }
            if(snapshot!=null && query.isBlank() && group=="Усі" && !onlyFavorites) item {
                PulseCard {
                    Text("Дані пристрою",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                    snapshot.metadata.forEach { (key,value)->
                        SelectionContainer {
                            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(key,color=Subtle,fontSize=10.sp)
                                Text(value,fontSize=12.sp)
                            }
                        }
                    }
                }
            }
            item {Spacer(Modifier.height(20.dp))}
        }
    }
    detail?.let { p->
        AlertDialog(onDismissRequest={detail=null},title={Text(TelemetryCatalog.title(p.key))},text={
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(p.display(),fontSize=24.sp,color=Mint)
                Text("Ключ: ${p.key}",fontSize=12.sp)
                Text("Одиниця API: ${p.unit.ifBlank {"не вказана"}}",fontSize=12.sp,color=Subtle)
                Text("Вимір: ${timeLabel(snapshot?.measuredAt ?: 0)}",fontSize=12.sp,color=Subtle)
                if(p.value==null) Text("Цей параметр підтримується пристроєм, але значення не надано у latest-відповіді.",fontSize=12.sp,color=Gold)
            }}
        },confirmButton={TextButton(onClick={detail=null}) {Text("Готово")}})
    }
}
@Composable fun ChartsScreen(snapshot: Snapshot?,samples: List<HistorySample>) {
    val numeric=snapshot?.points?.filter {it.number!=null}.orEmpty()
    var selected by remember {mutableStateOf("TotalConsumptionPower")}
    var menu by remember {mutableStateOf(false)}
    val point=numeric.firstOrNull {it.key==selected} ?: numeric.firstOrNull()
    val values=remember(samples,point?.key) {samples.mapNotNull {sample->point?.key?.let {key->sample.values[key]?.takeIf {sample.units[key]==null || sample.units[key]==point.unit}?.let {sample.at to it}}}}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("Історія вимірів","Графіки з реально отриманих даних")
        Box {
            OutlinedButton(onClick={menu=true},modifier=Modifier.fillMaxWidth()) {
                Text(point?.let {TelemetryCatalog.title(it.key)} ?: "Показників ще немає",modifier=Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore,null)
            }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false},modifier=Modifier.heightIn(max=350.dp)) {
                numeric.forEach {p->DropdownMenuItem(text={Text(TelemetryCatalog.title(p.key),fontSize=13.sp)},onClick={selected=p.key;menu=false})}
            }
        }
        PulseCard {
            SmallLabel(point?.key ?: "ІСТОРІЯ")
            Text(point?.display() ?: "—",fontSize=32.sp,fontWeight=FontWeight.Light,color=Mint)
            if(values.size<2) {
                Box(Modifier.fillMaxWidth().height(170.dp),contentAlignment=Alignment.Center) {
                    Text("Графік з’явиться після двох різних\nвимірів від інвертора.",color=Subtle,fontSize=13.sp)
                }
            } else {
                val min=values.minOf {it.second}
                val max=values.maxOf {it.second}
                val range=(max-min).takeIf {it>0} ?: 1.0
                val start=values.first().first
                val span=(values.last().first-start).coerceAtLeast(1L)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Мін. %.2f".format(min),color=Subtle,fontSize=11.sp)
                    Text("Макс. %.2f".format(max),color=Subtle,fontSize=11.sp)
                }
                Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                    val baseline=size.height-12.dp.toPx()
                    repeat(4) {i->
                        val y=size.height*i/3f
                        drawLine(Edge.copy(alpha=.6f),Offset(0f,y),Offset(size.width,y),1.dp.toPx())
                    }
                    val path=Path()
                    values.forEachIndexed {i,pair->
                        val x=((pair.first-start).toDouble()/span*size.width).toFloat()
                        val y=(baseline-(pair.second-min)/range*(baseline-12.dp.toPx())).toFloat()
                        if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                    }
                    val fill=Path().apply {addPath(path);lineTo(size.width,size.height);lineTo(0f,size.height);close()}
                    drawPath(fill,Brush.verticalGradient(listOf(Mint.copy(alpha=.25f),Color.Transparent)))
                    drawPath(path,Mint,style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(timeLabel(values.first().first),color=Subtle,fontSize=9.sp)
                    Text(timeLabel(values.last().first),color=Subtle,fontSize=9.sp)
                }
            }
            Text("${values.size} вимірів • до 288 збережених знімків",color=Subtle,fontSize=11.sp)
        }
        Text("Історія збирається на телефоні й зберігається після закриття. Повторні відповіді з тим самим часом не створюють нових точок.",color=Subtle,fontSize=12.sp,lineHeight=18.sp)
    }
}
@Composable fun EventsScreen(state: ScreenState) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {SectionTitle("Події та сигнали","Відключення, пороги АКБ та доставка сповіщень")}
        if(state.events.isEmpty()) item {PulseCard {
            Icon(Icons.Rounded.NotificationsNone,null,tint=Violet,modifier=Modifier.size(32.dp))
            Text("Подій поки немає",fontSize=18.sp)
            Text("Тут буде журнал останніх 100 повідомлень.",color=Subtle,fontSize=12.sp)
        }}
        items(state.events) {e->PulseCard {
            SmallLabel(timeLabel(e.at))
            Text(e.title,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
            Text(e.detail,fontSize=12.sp,color=Subtle,lineHeight=18.sp)
        }}
        item {Spacer(Modifier.height(20.dp))}
    }
}
