package com.powerhub.deya

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF101B24)
private val Panel = Color(0xFF1C293B)
private val Cyan = Color(0xFF35B7EE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel)) {
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
    var status by remember { mutableStateOf("Демо-режим · тестові значення") }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var cloudExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        config = ConfigStore.load(context)
        while (true) {
            val prefs = context.getSharedPreferences("powerhub_alert_state", android.content.Context.MODE_PRIVATE)
            val updated = prefs.getLong("latest_updated", 0L)
            if (updated > 0 && config.mode != "DEMO") {
                telemetry = Telemetry(
                    prefs.getString("latest_soc", "0")!!.toFloatOrNull() ?: 0f,
                    prefs.getString("latest_voltage", "0")!!.toFloatOrNull() ?: 0f,
                    prefs.getString("latest_load", "0")!!.toFloatOrNull() ?: 0f,
                    prefs.getString("latest_grid", "false") == "true",
                    config.mode
                )
                status = prefs.getString("monitor_status", status) ?: status
            }
            delay(1000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Navy) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("PowerHub", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("Deye Monitor", color = Cyan)
                }
                AssistChip(onClick = { tab = 1 }, label = { Text("Підключення") })
            }
            TabRow(selectedTabIndex = tab, containerColor = Navy) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Огляд") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Налаштування") })
            }
            if (tab == 0) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("ЗАРЯД АКУМУЛЯТОРА", color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
                            Text("${telemetry.soc.toInt()}%", color = Color.White, style = MaterialTheme.typography.displayMedium)
                            LinearProgressIndicator(progress = { (telemetry.soc / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                            Text("Deye · ${telemetry.source}", color = Cyan)
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
                    Text(status, color = Cyan, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { DataSource().read(config) } }
                                result.onSuccess { telemetry = it; status = "Підключено · дані отримано" }
                                    .onFailure { status = "Помилка: ${it.message}" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && config.mode != "DEMO"
                    ) { Text(if (busy) "З'єднання…" else "Оновити показники") }
                    Button(
                        onClick = {
                            scope.launch {
                                ConfigStore.save(context, config)
                                if (config.mode == "SOLARMAN_V5") {
                                    runCatching { PowerHubMonitorService.start(context) }
                                        .onSuccess { status = "Локальний моніторинг увімкнено · ${config.pollIntervalSeconds} с" }
                                        .onFailure { status = "Не вдалося запустити моніторинг: ${it.message}" }
                                } else if (config.mode == "HTTP_JSON") {
                                    val periodic = PeriodicWorkRequestBuilder<MonitoringWorker>(15, TimeUnit.MINUTES).build()
                                    WorkManager.getInstance(context).enqueueUniquePeriodicWork("powerhub-monitor", ExistingPeriodicWorkPolicy.UPDATE, periodic)
                                    WorkManager.getInstance(context).enqueueUniqueWork("powerhub-monitor-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MonitoringWorker>().build())
                                    status = "HTTP моніторинг увімкнено · Android може відкладати перевірки"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = config.mode != "DEMO"
                    ) { Text("Почати моніторинг") }
                    OutlinedButton(
                        onClick = {
                            PowerHubMonitorService.stop(context)
                            WorkManager.getInstance(context).cancelUniqueWork("powerhub-monitor")
                            WorkManager.getInstance(context).cancelUniqueWork("powerhub-monitor-now")
                            status = "Моніторинг зупинено"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Зупинити моніторинг") }
                    OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Налаштування підключення") }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Локальне підключення (Solarman V5)", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = {
                            searching = true
                            scope.launch {
                                val ip = withContext(Dispatchers.IO) { LoggerDiscovery.findSolarmanLogger() }
                                if (ip != null) {
                                    config = config.copy(modbusHost = ip, mode = "SOLARMAN_V5")
                                    status = "Логер знайдено: $ip"
                                } else status = "Логер не знайдено. Переконайся, що телефон і логер у тій самій Wi‑Fi мережі."
                                searching = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !searching
                    ) { Text(if (searching) "Шукаю логер у Wi‑Fi…" else "⌕  Автопошук інвертора в Wi‑Fi") }

                    PanelCard {
                        Field("IP-адреса Wi‑Fi логера", config.modbusHost) { config = config.copy(modbusHost = it, mode = "SOLARMAN_V5") }
                        Field("Серійний номер Wi‑Fi логера", config.loggerSerial) { config = config.copy(loggerSerial = it, mode = "SOLARMAN_V5") }
                        Field("Серійний номер інвертора (SN)", config.inverterSerial) { config = config.copy(inverterSerial = it) }
                        SettingSwitch("Трифазний інвертор (LP3)", config.threePhase) { config = config.copy(threePhase = it) }
                        if (config.threePhase) {
                            Text("Для трифазної моделі потрібна окрема карта регістрів. Моніторинг не стартуватиме до підтвердження точної моделі.", color = Color.Yellow, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text("2. Пороги сповіщень", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    PanelCard {
                        Field("Сповіщати, коли заряд АКБ нижче (%)", config.lowSoc.toInt().toString()) { config = config.copy(lowSoc = it.toFloatOrNull()?.coerceIn(1f, 99f) ?: config.lowSoc) }
                        Field("Ліміт навантаження без мережі (Вт)", config.overloadWatts.toString()) { config = config.copy(overloadWatts = it.toIntOrNull()?.coerceIn(100, 100000) ?: config.overloadWatts) }
                        Field("Інтервал опитування (секунд, мін. 3)", config.pollIntervalSeconds.toString()) { config = config.copy(pollIntervalSeconds = it.toIntOrNull()?.coerceIn(3, 60) ?: config.pollIntervalSeconds) }
                        SettingSwitch("Автозапуск після перезавантаження", config.autoStart) { config = config.copy(autoStart = it) }
                        Text("Для локального з'єднання опитування виконується у фоні з постійним системним сповіщенням.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }

                    PanelCard {
                        SettingSwitch("Резерв Deye Cloud (поза домом)", config.cloudEnabled) {
                            config = config.copy(cloudEnabled = it)
                            cloudExpanded = it
                        }
                        if (cloudExpanded || config.cloudEnabled) {
                            Field("Серійний номер інвертора (Inverter SN)", config.inverterSerial) { config = config.copy(inverterSerial = it) }
                            Field("Deye Cloud App ID", config.cloudAppId) { config = config.copy(cloudAppId = it) }
                            SecretField("Deye Cloud App Secret", config.cloudAppSecret) { config = config.copy(cloudAppSecret = it) }
                            Field("Email акаунта Deye Cloud", config.cloudEmail) { config = config.copy(cloudEmail = it) }
                            SecretField("Пароль акаунта Deye Cloud", config.cloudPassword) { config = config.copy(cloudPassword = it) }
                            Text("Поля зберігаються зашифровано на телефоні. Cloud-запити ще не активні — спершу треба підключити офіційний API.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Text("3. Telegram-сповіщення (необов'язково)", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    PanelCard {
                        SecretField("Telegram bot token", config.telegramToken) { config = config.copy(telegramToken = it) }
                        Field("Telegram chat ID", config.telegramChatId) { config = config.copy(telegramChatId = it) }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                ConfigStore.save(context, config)
                                if (config.mode == "SOLARMAN_V5" && config.autoStart) {
                                    runCatching { PowerHubMonitorService.start(context) }
                                }
                                status = "Налаштування збережено на телефоні"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Зберегти налаштування") }

                    OutlinedButton(
                        onClick = {
                            config = config.copy(mode = "HTTP_JSON")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Інше джерело: HTTP JSON") }

                    if (config.mode == "HTTP_JSON") {
                        PanelCard {
                            Field("API URL", config.endpoint) { config = config.copy(endpoint = it) }
                            Field("HTTP метод", config.method) { config = config.copy(method = it.uppercase()) }
                            Field("JSON headers", config.headersJson) { config = config.copy(headersJson = it) }
                            Field("POST body", config.body) { config = config.copy(body = it) }
                            Field("SOC path", config.socPath) { config = config.copy(socPath = it) }
                            Field("Battery voltage path", config.voltagePath) { config = config.copy(voltagePath = it) }
                            Field("Load kW path", config.loadPath) { config = config.copy(loadPath = it) }
                            Field("Grid connected path", config.gridPath) { config = config.copy(gridPath = it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun SecretField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation()
    )
}
