package com.powerhub.pulse

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink=Color(0xFF0B1117)
private val CardColor=Color(0xFF16212A)
private val Lime=Color(0xFFB9F56B)
private val Muted=Color(0xFF93A4B3)
private val Amber=Color(0xFFFFBB72)
class MainActivity: ComponentActivity() {
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted) Store.status("Дозволь сповіщення у налаштуваннях Android")
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        Notifier.channels(this)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Lime,onPrimary=Ink,background=Ink,surface=CardColor,secondary=Amber)) {
                App(
                    onPermission={
                        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
                    }
                )
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(onPermission: ()->Unit) {
    val c=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val state by Store.state.collectAsStateWithLifecycle()
    var s by remember { mutableStateOf(runCatching { Store.settings(c) }.getOrDefault(Settings())) }
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { now=System.currentTimeMillis(); delay(1000) } }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(message) { if(message.isNotBlank()) snackbar.showSnackbar(message) }
    fun task(action: suspend ()->String) {
        busy=true
        scope.launch {
            try { message=action() } catch(e: Exception) { message=e.message ?: "Не вдалося виконати дію" }
            finally { busy=false }
        }
    }
    Scaffold(
        containerColor=Ink,
        snackbarHost={ SnackbarHost(snackbar) },
        bottomBar={
            NavigationBar(containerColor=Ink) {
                listOf("Огляд","Події","Налаштування").forEachIndexed { i,title ->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},label={Text(title)},icon={
                        Icon(when(i) {0->Icons.Rounded.SpaceDashboard;1->Icons.Rounded.Notifications;else->Icons.Rounded.Tune},title)
                    })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=18.dp,bottom=24.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Column {
                    Text("DEYE",color=Muted,fontSize=12.sp,letterSpacing=4.sp)
                    Text("Pulse",fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color.White)
                }
                Surface(color=if(state.running) Lime.copy(alpha=.14f) else CardColor,shape=RoundedCornerShape(30.dp)) {
                    Text(if(state.running) "●  Моніторинг" else "○  Зупинено",
                        color=if(state.running) Lime else Muted,modifier=Modifier.padding(horizontal=14.dp,vertical=10.dp),fontSize=12.sp)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                when(tab) {
                    0 -> {
                        val r=state.reading
                        val stale=r==null || now-r.measuredAt>s.maxAgeSeconds*1000L
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Column {
                                Text("Твоя енергія.",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                                Text("Усе під контролем.",color=Muted,fontSize=15.sp)
                            }
                            Icon(Icons.Rounded.Bolt,null,tint=Lime,modifier=Modifier.size(38.dp))
                        }
                        Panel {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("АКУМУЛЯТОР",color=Muted,fontSize=12.sp,letterSpacing=2.sp)
                                Text(if(stale) "Немає свіжих даних" else "Актуальний вимір",color=if(stale) Amber else Lime,fontSize=12.sp)
                            }
                            Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center) {
                                Canvas(Modifier.size(200.dp)) {
                                    drawArc(Color(0xFF293640),135f,270f,false,style=Stroke(14.dp.toPx(),cap=StrokeCap.Round))
                                    r?.let { drawArc(if(stale) Muted else Lime,135f,270f*it.soc/100f,false,style=Stroke(14.dp.toPx(),cap=StrokeCap.Round)) }
                                }
                                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                    Text(r?.let { "${it.soc.toInt()}%" } ?: "—",fontSize=52.sp,fontWeight=FontWeight.Bold)
                                    Text("заряд АКБ",color=Muted)
                                }
                            }
                            Text(if(r==null) "Підключи акаунт Deye Cloud, щоб побачити показники." else "Вимір: "+date(r.measuredAt),
                                color=Muted,fontSize=12.sp)
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Metric("Споживання",r?.let { "%.2f".format(it.loadWatts/1000f) } ?: "—","кВт",Lime,Modifier.weight(1f))
                            Metric("Напруга АКБ",r?.let { "%.1f".format(it.volts) } ?: "—","В",Amber,Modifier.weight(1f))
                        }
                        Panel {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Column {
                                    Text("Зовнішня мережа",color=Muted,fontSize=12.sp)
                                    Text(if(stale) "Стан невідомий" else if(r!!.grid) "Світло є" else "Живлення від АКБ",
                                        color=if(stale) Amber else Lime,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                                }
                                Icon(Icons.Rounded.ElectricalServices,null,tint=if(stale) Muted else Lime,modifier=Modifier.size(32.dp))
                            }
                            HorizontalDivider(color=Muted.copy(alpha=.15f))
                            Text(state.status,color=Muted,fontSize=12.sp)
                            if(r!=null) Text("Вік даних: ${((now-r.measuredAt)/1000).coerceAtLeast(0)} с • ${r.source}",fontSize=12.sp,color=Muted)
                        }
                        if(state.history.size>1) Panel {
                            Text("Навантаження • останні виміри",color=Muted,fontSize=12.sp)
                            Canvas(Modifier.fillMaxWidth().height(65.dp)) {
                                val h=state.history; val max=(h.maxOrNull() ?: 1f).coerceAtLeast(1f)
                                for(i in 1 until h.size) drawLine(Lime,
                                    Offset(size.width*(i-1)/(h.size-1),size.height*(1-h[i-1]/max)),
                                    Offset(size.width*i/(h.size-1),size.height*(1-h[i]/max)),3.dp.toPx(),StrokeCap.Round)
                            }
                        }
                        Button(onClick={
                            if(state.running) MonitorService.stop(c)
                            else task {
                                withContext(Dispatchers.IO) { s.validate(); Store.save(c,s) }
                                MonitorService.start(c)
                                "Моніторинг запущено"
                            }
                        },enabled=!busy,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(16.dp)) {
                            Icon(if(state.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)
                            Spacer(Modifier.width(8.dp))
                            Text(if(state.running) "Зупинити моніторинг" else "Увімкнути моніторинг",fontWeight=FontWeight.Bold)
                        }
                        OutlinedButton(onClick={tab=2},modifier=Modifier.fillMaxWidth()) { Text("Підключення та перевірка") }
                        Text("Пороги: АКБ ≤ ${s.lowSoc}% • без мережі ≥ ${s.highWatts} Вт",color=Muted,fontSize=12.sp)
                    }
                    1 -> {
                        Text("Журнал подій",fontSize=26.sp,fontWeight=FontWeight.Bold)
                        Text("Останні 100 сигналів та помилок доставки.",color=Muted)
                        if(state.events.isEmpty()) Panel {
                            Icon(Icons.Rounded.CheckCircle,null,tint=Lime)
                            Text("Поки що тихо")
                            Text("Тут з’являться відключення, попередження й результати тестів.",color=Muted,fontSize=13.sp)
                        }
                        state.events.forEach { e -> Panel {
                            Text(date(e.at),color=Muted,fontSize=11.sp)
                            Text(e.title,fontWeight=FontWeight.SemiBold)
                            Text(e.detail,color=Muted,fontSize=13.sp)
                        } }
                    }
                    else -> {
                        Text("Підключення",fontSize=26.sp,fontWeight=FontWeight.Bold)
                        Text("Офіційний Deye Cloud API. ПК не потрібен.",color=Muted,fontSize=13.sp)
                        if(message.isNotBlank()) Panel { androidx.compose.foundation.text.selection.SelectionContainer { Text(message,color=Amber,fontSize=13.sp) } }
                        Panel {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected=s.source=="CLOUD",onClick={s=s.copy(source="CLOUD")},label={Text("Deye API")})
                                FilterChip(selected=s.source=="JSON",onClick={s=s.copy(source="JSON")},label={Text("Локальний API")})
                            }
                            if(s.source=="CLOUD") {
                                Text("Сервер акаунта",color=Muted,fontSize=12.sp)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    listOf("EU" to "https://eu1-developer.deyecloud.com","US" to "https://us1-developer.deyecloud.com",
                                        "India" to "https://india-developer.deyecloud.com").forEach { (name,url) ->
                                        FilterChip(selected=s.baseUrl==url,onClick={s=s.copy(baseUrl=url)},label={Text(name)})
                                    }
                                }
                                Input("App ID",s.appId) {s=s.copy(appId=it)}
                                Input("App Secret",s.secret,true) {s=s.copy(secret=it)}
                                Input("Email Deye Cloud",s.email) {s=s.copy(email=it)}
                                Input("Пароль Deye Cloud",s.password,true) {s=s.copy(password=it)}
                                Input("Серійний номер інвертора (SN)",s.inverterSn) {s=s.copy(inverterSn=it)}
                                Text("Вводь SN інвертора, а не Wi-Fi логера. Доступи шифруються на телефоні.",color=Muted,fontSize=12.sp)
                            } else {
                                Input("URL JSON API",s.jsonUrl) {s=s.copy(jsonUrl=it)}
                                Input("Заголовки JSON",s.jsonHeaders,true) {s=s.copy(jsonHeaders=it)}
                                Text("Поля відповіді: soc, batteryVoltage, loadWatts, gridPresent, timestamp (Unix, секунди).",color=Muted,fontSize=12.sp)
                            }
                            Button(onClick={task {
                                val r=withContext(Dispatchers.IO) { DeyeApi().read(s).also { Store.save(c,s) } }
                                Store.reading(c,r); Store.status("Перевірка успішна")
                                "Deye підключено. Показники отримано."
                            }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                Text(if(busy) "Перевіряю…" else "Зберегти й перевірити API")
                            }
                        }
                        Panel {
                            Text("Сигнали",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            NumberInput("Низький заряд (%)",s.lowSoc) {s=s.copy(lowSoc=it)}
                            NumberInput("Навантаження без мережі (Вт)",s.highWatts) {s=s.copy(highWatts=it)}
                            NumberInput("Підтвердження стану мережі (с)",s.confirmSeconds) {s=s.copy(confirmSeconds=it)}
                            NumberInput("Перевіряти API кожні (с)",s.pollSeconds) {s=s.copy(pollSeconds=it)}
                            Toggle("Автозапуск після перезавантаження",s.boot) {s=s.copy(boot=it)}
                            Text("30 секунд рахуються між підтвердженими вимірами. Затримка хмари додається до часу сповіщення.",color=Amber,fontSize=12.sp)
                            OutlinedButton(onClick=onPermission,modifier=Modifier.fillMaxWidth()) {Text("Дозвіл та звук сповіщень")}
                            OutlinedButton(onClick={
                                Notifier.notify(c,Alarm("test","Тест Deye Pulse","Системні сповіщення працюють. Це тестовий сигнал."))
                                message=if(c.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                                    "Тест надіслано. Перевір шторку сповіщень." else "Android забороняє сповіщення. Натисни кнопку дозволу."
                            },modifier=Modifier.fillMaxWidth()) {Text("Перевірити сигнал на телефоні")}
                            OutlinedButton(onClick={
                                c.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            },modifier=Modifier.fillMaxWidth()) {Text("Фонова робота та економія батареї")}
                            Text("Для роботи із заблокованим екраном дозволь фонову роботу Deye Pulse. Постійний моніторинг споживає заряд телефона.",color=Muted,fontSize=12.sp)
                        }
                        Panel {
                            Text("Telegram",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Input("Bot token",s.telegramToken,true) {s=s.copy(telegramToken=it)}
                            Input("Chat ID",s.telegramChat) {s=s.copy(telegramChat=it)}
                            Text("Спочатку надішли /start своєму боту.",color=Muted,fontSize=12.sp)
                            OutlinedButton(onClick={task {
                                withContext(Dispatchers.IO) { Notifier.telegram(s,Alarm("test","Deye Pulse підключено","Тестове повідомлення. Доставка працює.")) }
                                Store.log(c,"Тест Telegram","Повідомлення доставлено боту.")
                                "Telegram прийняв повідомлення."
                            }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Перевірити Telegram")}
                        }
                        if(s.source=="CLOUD") Panel {
                            Text("Показники API",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Text("Якщо Deye повертає інші назви показників, перевірка API покаже доступні ключі.",color=Muted,fontSize=12.sp)
                            Input("Ключ заряду",s.socKey) {s=s.copy(socKey=it)}
                            Input("Ключ напруги АКБ",s.voltageKey) {s=s.copy(voltageKey=it)}
                            Input("Ключ навантаження",s.loadKey) {s=s.copy(loadKey=it)}
                            Input("Ключ мережі",s.gridKey) {s=s.copy(gridKey=it)}
                            Toggle("Мережа визначається за напругою (В)",s.gridIsVoltage) {s=s.copy(gridIsVoltage=it)}
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                listOf("W","kW").forEach { unit ->
                                    FilterChip(selected=s.cloudLoadUnit==unit,onClick={s=s.copy(cloudLoadUnit=unit)},label={Text(unit)})
                                }
                            }
                            Text("Одиниця вище використовується, лише коли API не надав unit. Нульова потужність мережі не означає відключення.",color=Muted,fontSize=12.sp)
                            NumberInput("Максимальний вік даних (с)",s.maxAgeSeconds) {s=s.copy(maxAgeSeconds=it)}
                        }
                        Button(onClick={task {
                            withContext(Dispatchers.IO) {s.validate();Store.save(c,s)}
                            "Налаштування збережено."
                        }},enabled=!busy,modifier=Modifier.fillMaxWidth().height(52.dp)) {Text("Зберегти налаштування")}
                        Text("Deye Pulse 1.0 • новий окремий додаток",color=Muted,fontSize=11.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
@Composable private fun Panel(content: @Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp),color=CardColor,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable private fun Metric(title: String,value: String,unit: String,color: Color,modifier: Modifier) {
    Surface(shape=RoundedCornerShape(20.dp),color=CardColor,modifier=modifier) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,color=Muted,fontSize=12.sp)
            Text(value,color=color,fontSize=30.sp,fontWeight=FontWeight.SemiBold)
            Text(unit,color=Muted,fontSize=12.sp)
        }
    }
}
@Composable private fun Input(label: String,value: String,secret: Boolean=false,onValue: (String)->Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value=value,onValueChange=onValue,label={Text(label,fontSize=12.sp)},singleLine=true,
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),
        visualTransformation=if(secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon=if(secret) { { IconButton(onClick={visible=!visible}) {
            Icon(if(visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,"Показати або приховати")
        } } } else null)
}
@Composable private fun NumberInput(label: String,value: Int,onValue: (Int)->Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value=text,onValueChange={text=it;it.toIntOrNull()?.let(onValue)},label={Text(label,fontSize=12.sp)},
        singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
}
@Composable private fun Toggle(label: String,value: Boolean,onValue: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,modifier=Modifier.weight(1f),fontSize=13.sp)
        Switch(checked=value,onCheckedChange=onValue)
    }
}
private fun date(at: Long)=SimpleDateFormat("dd.MM • HH:mm:ss",Locale.getDefault()).format(Date(at))
