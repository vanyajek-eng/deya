package com.powerhub.deya

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy = Color(0xFF101B24)
private val Panel = Color(0xFF1A2A36)
private val Mint = Color(0xFF58D6A5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Navy, surface = Panel)) {
                PowerHubScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerHubScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf(AppConfig()) }
    var telemetry by remember { mutableStateOf(Telemetry.demo()) }
    var status by remember { mutableStateOf("Демо-режим: значення нижче тестові, не з інвертора.") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { config = ConfigStore.load(context) }
    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("PowerHub", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("Deye energy monitor", color = Mint)
                }
                AssistChip(onClick = { tab = 1 }, label = { Text("Налаштування") })
            }
            TabRow(selectedTabIndex = tab, containerColor = Navy) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Огляд") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("API / зв'язок") })
            }
            if (tab == 0) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("ЗАРЯД АКУМУЛЯТОРІВ", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                            Text("${telemetry.soc.toInt()}%", color = Color.White, style = MaterialTheme.typography.displayMedium)
                            LinearProgressIndicator(progress = { (telemetry.soc / 100f).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            Text("BMZ · ${telemetry.source}", color = Mint)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("Навантаження", "%.2f кВт".format(telemetry.loadKw), Modifier.weight(1f))
                        Metric("Напруга АКБ", "%.1f В".format(telemetry.batteryVoltage), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("Мережа", if (telemetry.gridPresent) "Є" else "Немає", Modifier.weight(1f))
                        Metric("Споживання", "%.0f Вт".format(telemetry.loadKw * 1000), Modifier.weight(1f))
                    }
                    Text(status, color = Mint, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { DataSource().read(config) } }
                            result.onSuccess { telemetry = it; status = "Дані успішно отримано: ${it.source}" }
                                .onFailure { status = "Помилка: ${it.message}" }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !busy && config.mode == "HTTP_JSON") {
                        Text(if (busy) "З'єднання…" else "Оновити показники")
                    }
                    OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Налаштувати джерело даних") }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Підключення та API", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Дані можна налаштувати без зміни коду. Demo — безпечний режим до введення реальних параметрів.", color = Color.LightGray)
                    Text("Тип джерела", color = Mint)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.mode == "DEMO", onClick = { config = config.copy(mode="DEMO") }, label = { Text("Демо") })
                        FilterChip(selected = config.mode == "HTTP_JSON", onClick = { config = config.copy(mode="HTTP_JSON") }, label = { Text("HTTP JSON") })
                        FilterChip(selected = config.mode == "MODBUS_TCP", onClick = { config = config.copy(mode="MODBUS_TCP") }, label = { Text("Modbus") })
                    }
                    Field("API URL", config.endpoint) { config = config.copy(endpoint=it) }
                    Field("HTTP метод", config.method) { config = config.copy(method=it.uppercase()) }
                    Field("JSON headers", config.headersJson) { config = config.copy(headersJson=it) }
                    Field("POST body", config.body) { config = config.copy(body=it) }
                    Text("Відповідність JSON полів", color = Mint)
                    Field("SOC path", config.socPath) { config = config.copy(socPath=it) }
                    Field("Battery voltage path", config.voltagePath) { config = config.copy(voltagePath=it) }
                    Field("Load kW path", config.loadPath) { config = config.copy(loadPath=it) }
                    Field("Grid connected path", config.gridPath) { config = config.copy(gridPath=it) }
                    Text("Modbus TCP (карта регістрів вводиться вручну)", color = Mint)
                    Field("IP / hostname", config.modbusHost) { config = config.copy(modbusHost=it) }
                    Field("Port", config.modbusPort.toString()) { config = config.copy(modbusPort=it.toIntOrNull() ?: 502) }
                    Field("Unit ID", config.unitId.toString()) { config = config.copy(unitId=it.toIntOrNull() ?: 1) }
                    Field("SOC register", config.socRegister.toString()) { config = config.copy(socRegister=it.toIntOrNull() ?: 0) }
                    Field("Load register", config.loadRegister.toString()) { config = config.copy(loadRegister=it.toIntOrNull() ?: 0) }
                    Field("Grid register", config.gridRegister.toString()) { config = config.copy(gridRegister=it.toIntOrNull() ?: 0) }
                    Text("Сповіщення", color = Mint)
                    Field("Низький заряд, %", config.lowSoc.toString()) { config = config.copy(lowSoc=it.toFloatOrNull() ?: 20f) }
                    Field("Telegram bot token", config.telegramToken) { config = config.copy(telegramToken=it) }
                    Field("Telegram chat ID", config.telegramChatId) { config = config.copy(telegramChatId=it) }
                    Button(onClick = { scope.launch { ConfigStore.save(context, config); status = "Налаштування збережено на телефоні." } }, modifier = Modifier.fillMaxWidth()) { Text("Зберегти налаштування") }
                    OutlinedButton(onClick = { status = config.json(); tab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Показати JSON конфігурацію") }
                }
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
            Text(value, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value=value, onValueChange=onValue, label={ Text(label) }, modifier=Modifier.fillMaxWidth(), singleLine=true)
}
