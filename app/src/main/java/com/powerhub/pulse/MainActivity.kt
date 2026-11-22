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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

val Night=Color(0xFF080D16)
val SurfaceBlue=Color(0xFF121C2B)
val Edge=Color(0xFF253348)
val Mint=Color(0xFF7DEDD0)
val Violet=Color(0xFFA9A0FF)
val Gold=Color(0xFFFFC282)
val Subtle=Color(0xFF8294AB)

class MainActivity: ComponentActivity() {
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this);Notifier.channels(this)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Night,secondary=Violet,
                background=Night,surface=SurfaceBlue,outline=Edge)) {
                PulseApp {
                    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName))
                }
            }
        }
    }
}
@Composable
private fun PulseApp(permission: ()->Unit) {
    val c=androidx.compose.ui.platform.LocalContext.current
    val state by Store.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var settings by remember { mutableStateOf(runCatching { Store.settings(c) }.getOrDefault(Settings())) }
    var page by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar=remember { SnackbarHostState() }
    var favoriteVersion by remember { mutableIntStateOf(0) }
    val favorites=remember(favoriteVersion) { Store.favorites(c) }
    LaunchedEffect(Unit) { while(true) { now=System.currentTimeMillis();delay(1000) } }
    LaunchedEffect(feedback) { if(feedback.isNotBlank()) snackbar.showSnackbar(feedback) }
    fun action(block: suspend ()->String) {
        if(working) return
        working=true
        scope.launch {
            feedback=try { block() } catch(e: Exception) { e.message ?: "Не вдалося виконати дію" }
            working=false
        }
    }
    val refresh: ()->Unit = {
        action {
            withContext(Dispatchers.IO) {settings.validate();Store.save(c,settings)}
            val snapshot=Sync.pull(c,settings)
            "Отримано ${snapshot.points.count { it.value!=null }} параметрів"
        }
    }
    val pin: ()->Unit = {
        feedback=if(runCatching { PulseWidget.pin(c) }.getOrDefault(false))
            "Підтвердь додавання віджета на головний екран"
        else "Затисни головний екран → Віджети → Deye Pulse"
    }
    Scaffold(
        containerColor=Night,snackbarHost={SnackbarHost(snackbar)},
        bottomBar={
            NavigationBar(containerColor=Night,tonalElevation=0.dp) {
                val titles=listOf("Огляд","Параметри","Графіки","Події","Ще")
                val icons=listOf(Icons.Rounded.Dashboard,Icons.Rounded.GridView,Icons.Rounded.ShowChart,Icons.Rounded.NotificationsNone,Icons.Rounded.Tune)
                titles.forEachIndexed { i,title->
                    NavigationBarItem(selected=page==i,onClick={page=i},icon={Icon(icons[i],title)},label={Text(title,fontSize=10.sp)},
                        colors=NavigationBarItemDefaults.colors(selectedIconColor=Mint,indicatorColor=Mint.copy(alpha=.12f),
                            selectedTextColor=Mint,unselectedIconColor=Subtle,unselectedTextColor=Subtle))
                }
            }
        }
    ) { padding->
        Column(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(Color(0xFF101B2C),Night),endY=700f))) {
            Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Mint.copy(alpha=.12f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                    Icon(Icons.Rounded.Bolt,null,tint=Mint)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("DEYE PULSE",fontSize=16.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp)
                    Text(state.snapshot?.sn?.takeIf { it.isNotBlank() }?.let {"Інвертор · $it"} ?: "Твоя домашня енергія",
                        fontSize=11.sp,color=Subtle)
                }
                IconButton(onClick=refresh,enabled=!state.loading && !working) {
                    if(state.loading || working) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Mint)
                    else Icon(Icons.Rounded.Refresh,"Оновити дані",tint=Mint)
                }
            }
            when(page) {
                0 -> Dashboard(state,settings,now,onParameters={page=1},onWidget=pin,onSetup={page=4},
                    onMonitor={
                        if(state.running) MonitorService.stop(c)
                        else action {
                            withContext(Dispatchers.IO) {settings.validate();Store.save(c,settings)}
                            MonitorService.start(c)
                            "Постійний моніторинг увімкнено"
                        }
                    })
                1 -> ParametersScreen(state.snapshot,favorites,onFavorite={Store.favorite(c,it);favoriteVersion++})
                2 -> ChartsScreen(state.snapshot,state.samples)
                3 -> EventsScreen(state)
                else -> SettingsScreen(settings,{settings=it},state,feedback,working,
                    connect={
                        action {
                            withContext(Dispatchers.IO) {settings.validate();Store.save(c,settings)}
                            val snapshot=Sync.pull(c,settings)
                            MonitorService.start(c)
                            "Підключено. Доступно ${snapshot.points.size} параметрів. Моніторинг працює."
                        }
                    },
                    save={action {withContext(Dispatchers.IO) {settings.validate();Store.save(c,settings)};"Налаштування збережено"}},
                    pin=pin,permission=permission,
                    testAlert={
                        Notifier.notify(c,Alarm("test","Deye Pulse • тест","Сповіщення на телефоні працюють."))
                        feedback=if(c.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                            "Тест надіслано у шторку сповіщень" else "Дозволь сповіщення Android"
                    },
                    testTelegram={action {
                        withContext(Dispatchers.IO) {Notifier.telegram(settings,Alarm("test","Deye Pulse підключено","Тест доставки повідомлень."))}
                        "Telegram прийняв повідомлення"
                    }})
            }
        }
    }
}
@Composable fun PulseCard(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier=modifier.fillMaxWidth(),color=SurfaceBlue,shape=RoundedCornerShape(26.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.045f))) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun SectionTitle(title: String,subtitle: String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.7).sp)
        subtitle?.let {Text(it,color=Subtle,fontSize=12.sp)}
    }
}
@Composable fun SmallLabel(text: String,color: Color=Subtle) { Text(text,color=color,fontSize=10.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.sp) }
@Composable fun Input(label: String,value: String,secret: Boolean=false,onValue: (String)->Unit) {
    var visible by remember {mutableStateOf(false)}
    OutlinedTextField(value=value,onValueChange=onValue,label={Text(label,fontSize=12.sp)},singleLine=true,
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),
        visualTransformation=if(secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon=if(secret) {{IconButton(onClick={visible=!visible}) {Icon(if(visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,"Приховати / показати")}}} else null)
}
@Composable fun NumberInput(label: String,value: Int,onValue: (Int)->Unit) {
    var text by remember(value) {mutableStateOf(value.toString())}
    OutlinedTextField(value=text,onValueChange={text=it;it.toIntOrNull()?.let(onValue)},label={Text(label,fontSize=12.sp)},
        singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number))
}
@Composable fun Toggle(label: String,value: Boolean,onValue: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(label,fontSize=13.sp,modifier=Modifier.weight(1f));Switch(checked=value,onCheckedChange=onValue)
    }
}
fun timeLabel(at: Long): String = if(at<=0) "Час невідомий" else SimpleDateFormat("dd.MM • HH:mm:ss",Locale.getDefault()).format(Date(at))
fun powerLabel(watts: Float?): String = watts?.let {"%.2f кВт".format(it/1000f)} ?: "—"
