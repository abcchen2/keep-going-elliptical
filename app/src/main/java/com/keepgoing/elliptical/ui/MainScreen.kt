package com.keepgoing.elliptical.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepgoing.elliptical.EllipticalController
import com.keepgoing.elliptical.model.ConnectionPhase
import com.keepgoing.elliptical.model.FoundDevice
import com.keepgoing.elliptical.model.FtmsResistanceEncoding
import com.keepgoing.elliptical.model.MachineState
import com.keepgoing.elliptical.model.ProtocolChoice
import com.keepgoing.elliptical.model.ProtocolKind
import com.keepgoing.elliptical.model.CalorieAlgorithm
import com.keepgoing.elliptical.model.WorkoutRecord
import com.keepgoing.elliptical.model.WorkoutStats
import com.keepgoing.elliptical.model.WorkoutStatus
import com.keepgoing.elliptical.protocol.ResistanceTables
import com.keepgoing.elliptical.workout.WorkoutTracker
import kotlin.math.roundToInt

@Composable
fun MainScreen(controller: EllipticalController, withBluetooth: (() -> Unit) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    val logs by controller.logs.collectAsStateWithLifecycle()
    val stats by controller.workout.stats.collectAsStateWithLifecycle()
    val history by controller.workout.history.collectAsStateWithLifecycle()

    val view = LocalView.current
    val active = state.phase == ConnectionPhase.READY || state.phase == ConnectionPhase.RECONNECTING
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    val inSession = state.phase != ConnectionPhase.IDLE && state.phase != ConnectionPhase.SCANNING

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header() }
        item { StatusCard(state, onDisconnect = { controller.disconnect() }) }
        if (inSession || stats.status != WorkoutStatus.IDLE) {
            item { WorkoutCard(stats, state, controller.workout) }
            if (state.protocol == ProtocolKind.DEMO && inSession) {
                item { DemoCard(controller) }
            }
        }
        if (inSession) {
            item { ResistanceCard(state, onSet = { controller.setResistance(it) }) }
        } else {
            item {
                ConnectCard(
                    scanning = state.phase == ConnectionPhase.SCANNING,
                    lastName = controller.settings.lastDeviceName ?: controller.settings.lastDeviceAddress,
                    onScan = { withBluetooth { controller.startScan() } },
                    onStopScan = { controller.stopScan() },
                    onConnectLast = { withBluetooth { controller.connectLast() } },
                    // Demo mode needs no Bluetooth permission (spec demo-mode, req 1.2).
                    onDemo = { controller.startDemo() },
                )
            }
            items(devices, key = { it.address }) { d ->
                DeviceRow(d, highlight = controller.likelyMachine(d)) {
                    withBluetooth { controller.connect(d.address, d.name) }
                }
            }
        }
        if (history.isNotEmpty()) item { HistoryCard(history, onClear = { controller.workout.clearHistory() }) }
        item { SettingsCard(controller) }
        item { DebugCard(controller, logs, connected = state.phase == ConnectionPhase.READY) }
    }
}

@Composable
private fun Header() {
    Column {
        Text("椭圆机续命", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "Keep Going Elliptical · 无需登录，直连莫比 (Mobi) 椭圆机调阻力",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun StatusCard(state: MachineState, onDisconnect: () -> Unit) {
    SectionCard("状态") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (state.phase) {
                ConnectionPhase.READY -> Color(0xFF2E7D32)
                ConnectionPhase.IDLE -> Color.Gray
                else -> Color(0xFFF9A825)
            }
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(8.dp))
            Text(state.phase.label, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (state.phase != ConnectionPhase.IDLE && state.phase != ConnectionPhase.SCANNING) {
                TextButton(onClick = onDisconnect) { Text("断开") }
            }
        }
        state.deviceName?.let { Text("设备: $it  ${state.deviceAddress.orEmpty()}") }
        state.protocol?.let { Text("协议: ${it.label}") }
        state.machineDescription?.let { Text("机型: $it") }
        val extra = listOfNotNull(
            state.modelNumber?.let { "型号 $it" },
            state.firmware?.let { "固件 $it" },
            state.battery?.takeIf { it > 0 }?.let { "电量 $it%" },
        )
        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ResistanceCard(state: MachineState, onSet: (Int) -> Unit) {
    SectionCard("阻力") {
        val min = state.resistanceMin
        val max = state.resistanceMax.coerceAtLeast(min + 1)
        var slider by remember { mutableFloatStateOf(state.targetResistance.toFloat()) }
        LaunchedEffect(state.targetResistance) { slider = state.targetResistance.toFloat() }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = { onSet(state.targetResistance - 1) },
                modifier = Modifier.size(84.dp),
                shape = RoundedCornerShape(20.dp),
                enabled = state.targetResistance > min,
            ) { Text("−", fontSize = 40.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    slider.roundToInt().toString(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "机器反馈: " + (state.reportedResistance?.toString() ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { onSet(state.targetResistance + 1) },
                modifier = Modifier.size(84.dp),
                shape = RoundedCornerShape(20.dp),
                enabled = state.targetResistance < max,
            ) { Text("+", fontSize = 40.sp) }
        }
        Slider(
            value = slider.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { slider = it },
            onValueChangeFinished = { onSet(slider.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            val presets = listOf(min, min + (max - min) / 4, min + (max - min) / 2, min + (max - min) * 3 / 4, max).distinct()
            presets.forEach { p ->
                OutlinedButton(
                    onClick = { onSet(p) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("$p") }
            }
        }
        Text(
            "范围 $min–$max（${state.resistanceRangeSource}）" +
                (state.resistanceMode?.let { " · 官方标记: ${ResistanceTables.modeText(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        if (!state.controlReady) {
            Text("设备初始化中，调节会在就绪后发送", color = Color(0xFFF9A825), style = MaterialTheme.typography.bodySmall)
        }
        if (state.protocol == ProtocolKind.HUANTONG) {
            Text(
                "环通主板没有阻力回读，本 App 每秒发送一次当前档位（与官方一致），请保持 App 在前台。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        if (state.protocol == ProtocolKind.V1 && state.reportedResistance == null) {
            Text("1.0 盒子需要先收到机器状态帧，请踩动几圈椭圆机。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun formatDuration(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)

private fun formatDistance(m: Double): String = if (m >= 1000) "%.2f km".format(m / 1000) else "%.0f m".format(m)

@Composable
private fun WorkoutCard(stats: WorkoutStats, state: MachineState, tracker: WorkoutTracker) {
    val now = android.os.SystemClock.elapsedRealtime()
    val liveRpm = state.rpm?.takeIf { it > 0 && now - state.rpmUpdatedAt <= 5000 }
    val liveHr = state.heartRate?.takeIf { now - state.heartRateUpdatedAt <= 5000 }

    SectionCard("本次锻炼 · ${stats.status.label}") {
        Text(
            formatDuration(stats.activeSec),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth()) {
            Metric("里程", formatDistance(stats.distanceM), Modifier.weight(1f))
            Metric("热量 kcal", "%.1f".format(stats.calories), Modifier.weight(1f))
            Metric("心率", liveHr?.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Metric("踏频 rpm", liveRpm?.let { "%.0f".format(it) } ?: "0", Modifier.weight(1f))
            Metric("速度 km/h", "%.1f".format(stats.speedKmh), Modifier.weight(1f))
            Metric("消耗 kcal/分", "%.1f".format(stats.kcalPerMin), Modifier.weight(1f))
        }
        Text(
            listOfNotNull(
                "平均踏频 %.0f".format(stats.avgRpm),
                "平均阻力 %.1f".format(stats.avgResistance),
                stats.avgHeartRate?.let { "平均心率 $it" },
                stats.maxHeartRate?.let { "最高心率 $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "里程: ${stats.distanceSource.label} · 热量: ${stats.calorieSource.label}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (stats.status) {
                WorkoutStatus.IDLE -> {
                    Button(onClick = { tracker.start() }, modifier = Modifier.weight(1f)) { Text("开始") }
                }
                WorkoutStatus.RUNNING, WorkoutStatus.AUTO_PAUSED -> {
                    OutlinedButton(onClick = { tracker.pause() }, modifier = Modifier.weight(1f)) { Text("暂停") }
                    Button(onClick = { tracker.finish() }, modifier = Modifier.weight(1f)) { Text("结束") }
                }
                WorkoutStatus.PAUSED -> {
                    Button(onClick = { tracker.resume() }, modifier = Modifier.weight(1f)) { Text("继续") }
                    OutlinedButton(onClick = { tracker.finish() }, modifier = Modifier.weight(1f)) { Text("结束") }
                }
                WorkoutStatus.FINISHED -> {
                    OutlinedButton(onClick = { tracker.reset() }, modifier = Modifier.weight(1f)) { Text("关闭") }
                    Button(onClick = { tracker.start() }, modifier = Modifier.weight(1f)) { Text("开始新锻炼") }
                }
            }
        }
        if (stats.status == WorkoutStatus.IDLE) {
            Text("开始踩动会自动开始计时，停止踩动一段时间会自动暂停。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        state.machineRunState?.let { Text("机器状态: $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
    }
}

@Composable
private fun DemoCard(controller: EllipticalController) {
    var pedalling by remember { mutableStateOf(controller.demoPedalling) }
    SectionCard("演示模式") {
        Text(
            "当前是模拟椭圆机：踏频随阻力变化，心率随强度上升，阻力 300 毫秒后回读。关掉“正在踩动”可以看到自动暂停。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        SwitchRow("正在踩动", pedalling) {
            pedalling = it
            controller.demoPedalling = it
        }
    }
}

@Composable
private fun HistoryCard(history: List<WorkoutRecord>, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    SectionCard("锻炼记录") {
        val shown = if (expanded) history else history.take(3)
        shown.forEach { r ->
            Column {
                Text(
                    "${fmt.format(java.util.Date(r.startedAt))}  ${formatDuration(r.activeSec)}  " +
                        "${formatDistance(r.distanceM)}  %.0f kcal".format(r.calories),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOfNotNull(
                        "平均踏频 %.0f".format(r.avgRpm),
                        "平均阻力 %.1f".format(r.avgResistance),
                        r.avgHeartRate?.let { "平均心率 $it" },
                        r.maxHeartRate?.let { "最高 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
        Row {
            if (history.size > 3) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "全部 ${history.size} 条") }
            }
            TextButton(onClick = onClear) { Text("清空记录") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "—", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun ConnectCard(
    scanning: Boolean,
    lastName: String?,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectLast: () -> Unit,
    onDemo: () -> Unit,
) {
    SectionCard("连接椭圆机") {
        Text(
            "先给椭圆机通电并踩几圈唤醒蓝牙盒子。不要同时打开官方 App。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) {
                OutlinedButton(onClick = onStopScan) { Text("停止扫描") }
            } else {
                Button(onClick = onScan) { Text("扫描设备") }
            }
            if (lastName != null) {
                OutlinedButton(onClick = onConnectLast) { Text("连接上次: $lastName", maxLines = 1) }
            }
        }
        HorizontalDivider()
        Text("没有椭圆机？可以先用演示模式体验全部功能。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        FilledTonalButton(onClick = onDemo) { Text("演示模式 Demo（无需设备）") }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, highlight: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name ?: "(无名称)", fontWeight = FontWeight.Medium)
                Text(
                    d.address + if (d.serviceHints.isNotEmpty()) "  服务: " + d.serviceHints.joinToString(",") else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsCard(controller: EllipticalController) {
    var expanded by remember { mutableStateOf(false) }
    val s = controller.settings
    SectionCard("设置") {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开（协议、体重、热量、最大阻力）") }
        if (!expanded) return@SectionCard

        var protocol by remember { mutableStateOf(s.protocolChoice) }
        Text("协议（连接时生效）", fontWeight = FontWeight.Medium)
        ProtocolChoice.entries.forEach { c ->
            RadioRow(c.label, protocol == c) { protocol = c; s.protocolChoice = c }
        }
        HorizontalDivider()

        var enc by remember { mutableStateOf(s.ftmsEncoding) }
        Text("FTMS 阻力编码", fontWeight = FontWeight.Medium)
        FtmsResistanceEncoding.entries.forEach { e ->
            RadioRow(e.label, enc == e) { enc = e; s.ftmsEncoding = e }
        }
        HorizontalDivider()

        var algo by remember { mutableStateOf(s.calorieAlgorithm) }
        Text("热量算法（机器不上报热量时使用）", fontWeight = FontWeight.Medium)
        CalorieAlgorithm.entries.forEach { a ->
            RadioRow(a.label, algo == a) { algo = a; s.calorieAlgorithm = a }
        }
        HorizontalDivider()

        var weight by remember { mutableStateOf(s.userWeightKg.toString()) }
        var maxOverride by remember { mutableStateOf(s.maxResistanceOverride.toString()) }
        var factor by remember { mutableStateOf(s.calorieFactorPercent.toString()) }
        var autoPause by remember { mutableStateOf(s.autoPauseSec.toString()) }
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filter(Char::isDigit).take(3) },
            label = { Text("体重 kg（环通主板和老算法热量需要）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxOverride,
            onValueChange = { maxOverride = it.filter(Char::isDigit).take(3) },
            label = { Text("最大阻力覆盖（0 = 按设备/官方表）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = factor,
            onValueChange = { factor = it.filter(Char::isDigit).take(3) },
            label = { Text("热量系数 %（30–200，校准用，默认 100）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = autoPause,
            onValueChange = { autoPause = it.filter(Char::isDigit).take(3) },
            label = { Text("停止踩动多少秒后自动暂停（0 = 不自动暂停，官方 60）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            weight.toIntOrNull()?.let { s.userWeightKg = it }
            maxOverride.toIntOrNull()?.let { s.maxResistanceOverride = it }
            factor.toIntOrNull()?.let { s.calorieFactorPercent = it }
            autoPause.toIntOrNull()?.let { s.autoPauseSec = it }
            weight = s.userWeightKg.toString()
            factor = s.calorieFactorPercent.toString()
            autoPause = s.autoPauseSec.toString()
            controller.refreshRange()
        }) { Text("保存") }
        HorizontalDivider()

        var reconnect by remember { mutableStateOf(s.autoReconnect) }
        SwitchRow("断线自动重连", reconnect) { reconnect = it; s.autoReconnect = it }
        var verbose by remember { mutableStateOf(controller.verboseNotify) }
        SwitchRow("日志记录所有通知帧（调试用）", verbose) { verbose = it; controller.verboseNotify = it }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DebugCard(controller: EllipticalController, logs: List<String>, connected: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    SectionCard("日志与调试") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) { Text("复制日志") }
            TextButton(onClick = { controller.clearLogs() }) { Text("清空") }
        }
        if (!expanded) {
            logs.lastOrNull()?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2) }
            return@SectionCard
        }
        if (connected) {
            var ch by remember { mutableStateOf("880f") }
            var hex by remember { mutableStateOf("") }
            var result by remember { mutableStateOf<String?>(null) }
            Text("发送原始指令（高级）", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ch,
                    onValueChange = { ch = it.trim() },
                    label = { Text("特征") },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("十六进制，如 02 01 05") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = { result = controller.sendRaw(ch, hex) ?: "已加入发送队列" }) { Text("发送") }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
        }
        Spacer(Modifier.height(4.dp))
        logs.takeLast(150).asReversed().forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
