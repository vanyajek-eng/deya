package com.powerhub.pulse

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun SettingsScreen(
    s: Settings,onSettings: (Settings)->Unit,state: ScreenState,feedback: String,working: Boolean,
    connect: ()->Unit,save: ()->Unit,pin: ()->Unit,permission: ()->Unit,testAlert: ()->Unit,testTelegram: ()->Unit
) {
    val c=androidx.compose.ui.platform.LocalContext.current
    var advanced by remember {mutableStateOf(false)}
    var widgetKeys by remember {mutableStateOf(Store.widgetKeys(c))}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionTitle("Твій Deye","Підключення, віджет і сповіщення")
        if(feedback.isNotBlank()) PulseCard {
            SelectionContainer {Text(feedback,fontSize=12.sp,color=Gold)}
        }
        PulseCard {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.CloudDone,null,tint=Mint)
                Column {Text("Deye Cloud API",fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text("Доступ з будь-якого місця • без ПК",color=Subtle,fontSize=11.sp)}
            }
            if(s.source=="CLOUD") {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("EU" to "https://eu1-developer.deyecloud.com","US" to "https://us1-developer.deyecloud.com",
                        "India" to "https://india-developer.deyecloud.com").forEach { (label,url)->
                        FilterChip(selected=s.baseUrl==url,onClick={onSettings(s.copy(baseUrl=url))},label={Text(label)})
                    }
                }
                Input("App ID",s.appId) {onSettings(s.copy(appId=it))}
                Input("App Secret",s.secret,true) {onSettings(s.copy(secret=it))}
                Input("Email Deye Cloud",s.email) {onSettings(s.copy(email=it))}
                Input("Пароль Deye Cloud",s.password,true) {onSettings(s.copy(password=it))}
                Input("Серійний номер інвертора (SN)",s.inverterSn) {onSettings(s.copy(inverterSn=it))}
            } else {
                Input("URL JSON API",s.jsonUrl) {onSettings(s.copy(jsonUrl=it))}
                Input("Заголовки JSON",s.jsonHeaders,true) {onSettings(s.copy(jsonHeaders=it))}
                Text("soc, batteryVoltage, loadWatts, gridPresent, timestamp (Unix-секунди).",fontSize=11.sp,color=Subtle)
            }
            Button(onClick=connect,enabled=!working,modifier=Modifier.fillMaxWidth().height(50.dp)) {
                Text(if(working) "Підключення…" else "Зберегти й підключити")
            }
            Text("Підключення одразу запускає моніторинг. Доступи зберігаються зашифровано лише на телефоні.",color=Subtle,fontSize=11.sp,lineHeight=16.sp)
        }
        PulseCard {
            Icon(Icons.Rounded.Widgets,null,tint=Violet,modifier=Modifier.size(28.dp))
            Text("Готовий віджет",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Text("Чотири показники, стан мережі, вік виміру й кнопка оновлення — на головному екрані.",color=Subtle,fontSize=12.sp,lineHeight=18.sp)
            val available=state.snapshot?.points.orEmpty()
            widgetKeys.forEachIndexed { index,key->
                var expanded by remember(index) {mutableStateOf(false)}
                Box {
                    OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {
                        Text("${index+1}. ${TelemetryCatalog.title(key)}",modifier=Modifier.weight(1f),fontSize=12.sp)
                        Icon(Icons.Rounded.ExpandMore,null)
                    }
                    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=300.dp)) {
                        available.forEach {p->
                            DropdownMenuItem(text={Column {Text(TelemetryCatalog.title(p.key),fontSize=12.sp);Text(p.key,fontSize=9.sp,color=Subtle)}},onClick={
                                val keys=widgetKeys.toMutableList();keys[index]=p.key;widgetKeys=keys
                                Store.widgetKeys(c,keys);expanded=false
                            })
                        }
                        if(available.isEmpty()) DropdownMenuItem(text={Text("Спочатку підключи API")},onClick={expanded=false})
                    }
                }
            }
            Button(onClick=pin,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Violet,onContainerColor=Night)) {
                Text("Додати віджет на головний екран")
            }
            Text("Під час моніторингу оновлюється разом із API. Без постійного моніторингу Android виконує періодичні перевірки приблизно раз на 15 хв або пізніше. Кнопка ↻ запитує оновлення без входу в додаток.",color=Subtle,fontSize=11.sp,lineHeight=16.sp)
        }
        PulseCard {
            Text("Сигнали",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            NumberInput("Низький заряд (%)",s.lowSoc) {onSettings(s.copy(lowSoc=it))}
            NumberInput("Навантаження без мережі (Вт)",s.highWatts) {onSettings(s.copy(highWatts=it))}
            NumberInput("Підтвердження стану мережі (с)",s.confirmSeconds) {onSettings(s.copy(confirmSeconds=it))}
            NumberInput("Опитування API кожні (с)",s.pollSeconds) {onSettings(s.copy(pollSeconds=it))}
            NumberInput("Максимальний вік для сигналів (с)",s.maxAgeSeconds) {onSettings(s.copy(maxAgeSeconds=it))}
            Toggle("Відновлювати моніторинг після перезавантаження",s.boot) {onSettings(s.copy(boot=it))}
            Text("Для підтвердження зміни мережі потрібні різні виміри. Швидкість сигналу обмежена оновленням логера та хмари.",fontSize=11.sp,color=Gold,lineHeight=17.sp)
            OutlinedButton(onClick=permission,modifier=Modifier.fillMaxWidth()) {Text("Дозвіл і звук сповіщень")}
            OutlinedButton(onClick=testAlert,modifier=Modifier.fillMaxWidth()) {Text("Тест сигналу на телефоні")}
            OutlinedButton(onClick={c.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))},modifier=Modifier.fillMaxWidth()) {
                Text("Налаштувати фонову роботу")
            }
        }
        PulseCard {
            Text("Telegram",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Input("Bot token",s.telegramToken,true) {onSettings(s.copy(telegramToken=it))}
            Input("Chat ID",s.telegramChat) {onSettings(s.copy(telegramChat=it))}
            Text("Спочатку надішли /start своєму боту.",fontSize=11.sp,color=Subtle)
            OutlinedButton(onClick=testTelegram,enabled=!working,modifier=Modifier.fillMaxWidth()) {Text("Перевірити Telegram")}
        }
        PulseCard {
            TextButton(onClick={advanced=!advanced},modifier=Modifier.fillMaxWidth()) {
                Text("Розширене підключення",modifier=Modifier.weight(1f))
                Icon(if(advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,null)
            }
            if(advanced) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=s.source=="CLOUD",onClick={onSettings(s.copy(source="CLOUD"))},label={Text("Deye Cloud")})
                    FilterChip(selected=s.source=="JSON",onClick={onSettings(s.copy(source="JSON"))},label={Text("Локальний JSON")})
                }
                Text("Усі параметри показуються автоматично. Ці поля лише уточнюють показники огляду та сигналів.",fontSize=11.sp,color=Subtle,lineHeight=16.sp)
                Input("Ключ заряду АКБ",s.socKey) {onSettings(s.copy(socKey=it))}
                Input("Ключ напруги АКБ",s.voltageKey) {onSettings(s.copy(voltageKey=it))}
                Input("Ключ навантаження",s.loadKey) {onSettings(s.copy(loadKey=it))}
                Input("Ключ стану / напруги мережі",s.gridKey) {onSettings(s.copy(gridKey=it))}
                Toggle("Мережа визначається за напругою",s.gridIsVoltage) {onSettings(s.copy(gridIsVoltage=it))}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("W","kW").forEach {unit->FilterChip(selected=s.cloudLoadUnit==unit,onClick={onSettings(s.copy(cloudLoadUnit=unit))},label={Text(unit)})}
                }
                Text("Одиниця вище — резервна, якщо API не вказав unit. GridVoltageL1L2 підбирається автоматично, коли налаштований ключ відсутній.",fontSize=11.sp,color=Subtle,lineHeight=16.sp)
            }
        }
        Button(onClick=save,enabled=!working,modifier=Modifier.fillMaxWidth().height(52.dp)) {Text("Зберегти налаштування")}
        Text("DEYE PULSE 2.0 • повний каталог телеметрії",color=Subtle,fontSize=10.sp,modifier=Modifier.padding(bottom=20.dp))
    }
}
