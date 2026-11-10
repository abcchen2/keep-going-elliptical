package com.keepgoing.elliptical.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.keepgoing.elliptical.EllipticalController
import com.keepgoing.elliptical.R
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

/** Bottom navigation destinations. Workout is the start tab; Back from another tab returns to it. */
private enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector) {
    WORKOUT(R.string.tab_workout, Icons.Filled.PlayArrow),
    HISTORY(R.string.tab_history, Icons.Filled.DateRange),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

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

    var tab by rememberSaveable { mutableStateOf(Tab.WORKOUT) }
    BackHandler(enabled = tab != Tab.WORKOUT) { tab = Tab.WORKOUT }
    // One scroll state per tab so switching tabs keeps each tab's position.
    val workoutList = rememberLazyListState()
    val historyList = rememberLazyListState()
    val settingsList = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tab, onSelect = { tab = it }) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (tab) {
            Tab.WORKOUT -> TabColumn(modifier, workoutList) {
                if (inSession) {
                    item { SessionBar(state, onDisconnect = { controller.disconnect() }) }
                } else {
                    item { Header() }
                    item { StatusCard(state, onDisconnect = { controller.disconnect() }) }
                }
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
            }
            Tab.HISTORY -> TabColumn(modifier, historyList) {
                item { HistoryHeader(hasRecords = history.isNotEmpty(), onClear = { controller.workout.clearHistory() }) }
                items(history) { HistoryRow(it) }
            }
            Tab.SETTINGS -> TabColumn(modifier, settingsList) {
                // Full device details (protocol, model, firmware, battery) live here while connected;
                // the Workout tab only shows the compact session bar.
                if (inSession) item { StatusCard(state, onDisconnect = { controller.disconnect() }) }
                item { SettingsCard(controller) }
                item { DebugCard(controller, logs, connected = state.phase == ConnectionPhase.READY) }
            }
        }
    }
}

@Composable
private fun TabColumn(modifier: Modifier, listState: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = null) },
                label = { Text(stringResource(t.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                ),
            )
        }
    }
}

/** One-line connection status for the Workout tab while connected, so the metrics and resistance fit on screen. */
@Composable
private fun SessionBar(state: MachineState, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseDot(state.phase)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(state.phase.labelRes), fontWeight = FontWeight.Medium)
                state.deviceName?.let {
                    Text(
                        " · $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
            }
            state.lastError?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PhaseDot(phase: ConnectionPhase) {
    val color = when (phase) {
        ConnectionPhase.READY -> Color(0xFF2E7D32)
        ConnectionPhase.IDLE -> Color.Gray
        else -> Color(0xFFF9A825)
    }
    Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
}

@Composable
private fun Header() {
    Column {
        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.app_subtitle),
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
    SectionCard(stringResource(R.string.card_status)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseDot(state.phase)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(state.phase.labelRes), fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (state.phase != ConnectionPhase.IDLE && state.phase != ConnectionPhase.SCANNING) {
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.action_disconnect)) }
            }
        }
        state.deviceName?.let { Text(stringResource(R.string.label_device, it, state.deviceAddress.orEmpty())) }
        state.protocol?.let { Text(stringResource(R.string.label_protocol, stringResource(it.labelRes))) }
        state.machineDescription?.let { Text(stringResource(R.string.label_machine, it.resolve(LocalContext.current))) }
        val extra = listOfNotNull(
            state.modelNumber?.let { stringResource(R.string.label_model, it) },
            state.firmware?.let { stringResource(R.string.label_firmware, it) },
            state.battery?.takeIf { it > 0 }?.let { stringResource(R.string.label_battery, it) },
        )
        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        state.lastError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ResistanceCard(state: MachineState, onSet: (Int) -> Unit) {
    SectionCard(stringResource(R.string.card_resistance)) {
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
                    stringResource(R.string.label_machine_feedback, state.reportedResistance?.toString() ?: "—"),
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
            stringResource(R.string.label_range, min, max, stringResource(state.resistanceRangeSource)).let { range ->
                state.resistanceMode?.let {
                    stringResource(R.string.label_range_mode, range, stringResource(ResistanceTables.modeText(it)))
                } ?: range
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        if (!state.controlReady) {
            Text(stringResource(R.string.hint_setting_up), color = Color(0xFFF9A825), style = MaterialTheme.typography.bodySmall)
        }
        if (state.protocol == ProtocolKind.HUANTONG) {
            Text(
                stringResource(R.string.hint_huantong),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        if (state.protocol == ProtocolKind.V1 && state.reportedResistance == null) {
            Text(stringResource(R.string.hint_v1), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun formatDuration(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)

@Composable
private fun formatDistance(m: Double): String =
    if (m >= 1000) stringResource(R.string.unit_km, m / 1000) else stringResource(R.string.unit_m, m)

@Composable
private fun WorkoutCard(stats: WorkoutStats, state: MachineState, tracker: WorkoutTracker) {
    val now = android.os.SystemClock.elapsedRealtime()
    val liveRpm = state.rpm?.takeIf { it > 0 && now - state.rpmUpdatedAt <= 5000 }
    val liveHr = state.heartRate?.takeIf { now - state.heartRateUpdatedAt <= 5000 }

    SectionCard(stringResource(R.string.card_workout, stringResource(stats.status.labelRes))) {
        Text(
            formatDuration(stats.activeSec),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth()) {
            Metric(stringResource(R.string.metric_distance), formatDistance(stats.distanceM), Modifier.weight(1f))
            Metric(stringResource(R.string.metric_calories), "%.1f".format(stats.calories), Modifier.weight(1f))
            Metric(stringResource(R.string.metric_heart_rate), liveHr?.toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Metric(stringResource(R.string.metric_cadence), liveRpm?.let { "%.0f".format(it) } ?: "0", Modifier.weight(1f))
            Metric(stringResource(R.string.metric_speed), "%.1f".format(stats.speedKmh), Modifier.weight(1f))
            Metric(stringResource(R.string.metric_burn_rate), "%.1f".format(stats.kcalPerMin), Modifier.weight(1f))
        }
        Text(
            listOfNotNull(
                stringResource(R.string.stat_avg_rpm, stats.avgRpm),
                stringResource(R.string.stat_avg_resistance, stats.avgResistance),
                stats.avgHeartRate?.let { stringResource(R.string.stat_avg_hr, it) },
                stats.maxHeartRate?.let { stringResource(R.string.stat_max_hr, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.label_sources, stringResource(stats.distanceSource.labelRes), stringResource(stats.calorieSource.labelRes)),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when (stats.status) {
                WorkoutStatus.IDLE -> {
                    Button(onClick = { tracker.start() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_start)) }
                }
                WorkoutStatus.RUNNING, WorkoutStatus.AUTO_PAUSED -> {
                    OutlinedButton(onClick = { tracker.pause() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_pause)) }
                    Button(onClick = { tracker.finish() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_finish)) }
                }
                WorkoutStatus.PAUSED -> {
                    Button(onClick = { tracker.resume() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_resume)) }
                    OutlinedButton(onClick = { tracker.finish() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_finish)) }
                }
                WorkoutStatus.FINISHED -> {
                    OutlinedButton(onClick = { tracker.reset() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_close)) }
                    Button(onClick = { tracker.start() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_new_workout)) }
                }
            }
        }
        if (stats.status == WorkoutStatus.IDLE) {
            Text(stringResource(R.string.hint_auto_start), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        state.machineRunState?.let {
            Text(stringResource(R.string.label_machine_state, stringResource(it)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun DemoCard(controller: EllipticalController) {
    var pedalling by remember { mutableStateOf(controller.demoPedalling) }
    SectionCard(stringResource(R.string.card_demo)) {
        Text(
            stringResource(R.string.hint_demo),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        SwitchRow(stringResource(R.string.label_pedalling), pedalling) {
            pedalling = it
            controller.demoPedalling = it
        }
    }
}

@Composable
private fun HistoryHeader(hasRecords: Boolean, onClear: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.card_history), fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (hasRecords) TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_history)) }
        }
        if (!hasRecords) {
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
    }
}

@Composable
private fun HistoryRow(r: WorkoutRecord) {
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(
                    R.string.history_line,
                    fmt.format(java.util.Date(r.startedAt)),
                    formatDuration(r.activeSec),
                    formatDistance(r.distanceM),
                    r.calories,
                ),
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOfNotNull(
                    stringResource(R.string.stat_avg_rpm, r.avgRpm),
                    stringResource(R.string.stat_avg_resistance, r.avgResistance),
                    r.avgHeartRate?.let { stringResource(R.string.stat_avg_hr, it) },
                    r.maxHeartRate?.let { stringResource(R.string.stat_max_hr, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
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
    SectionCard(stringResource(R.string.card_connect)) {
        Text(
            stringResource(R.string.hint_connect),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanning) {
                OutlinedButton(onClick = onStopScan) { Text(stringResource(R.string.action_stop_scan)) }
            } else {
                Button(onClick = onScan) { Text(stringResource(R.string.action_scan)) }
            }
            if (lastName != null) {
                OutlinedButton(onClick = onConnectLast) { Text(stringResource(R.string.action_connect_last, lastName), maxLines = 1) }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.hint_no_machine), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        FilledTonalButton(onClick = onDemo) { Text(stringResource(R.string.action_demo)) }
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
                Text(d.name ?: stringResource(R.string.label_no_name), fontWeight = FontWeight.Medium)
                Text(
                    d.address + if (d.serviceHints.isNotEmpty()) {
                        "  " + stringResource(R.string.label_services, d.serviceHints.joinToString(","))
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsCard(controller: EllipticalController) {
    val s = controller.settings
    SectionCard(stringResource(R.string.card_settings)) {
        var protocol by remember { mutableStateOf(s.protocolChoice) }
        Text(stringResource(R.string.settings_protocol), fontWeight = FontWeight.Medium)
        ProtocolChoice.entries.forEach { c ->
            RadioRow(stringResource(c.labelRes), protocol == c) { protocol = c; s.protocolChoice = c }
        }
        HorizontalDivider()

        var enc by remember { mutableStateOf(s.ftmsEncoding) }
        Text(stringResource(R.string.settings_ftms_encoding), fontWeight = FontWeight.Medium)
        FtmsResistanceEncoding.entries.forEach { e ->
            RadioRow(stringResource(e.labelRes), enc == e) { enc = e; s.ftmsEncoding = e }
        }
        HorizontalDivider()

        var algo by remember { mutableStateOf(s.calorieAlgorithm) }
        Text(stringResource(R.string.settings_calorie_algorithm), fontWeight = FontWeight.Medium)
        CalorieAlgorithm.entries.forEach { a ->
            RadioRow(stringResource(a.labelRes), algo == a) { algo = a; s.calorieAlgorithm = a }
        }
        HorizontalDivider()

        var weight by remember { mutableStateOf(s.userWeightKg.toString()) }
        var maxOverride by remember { mutableStateOf(s.maxResistanceOverride.toString()) }
        var factor by remember { mutableStateOf(s.calorieFactorPercent.toString()) }
        var autoPause by remember { mutableStateOf(s.autoPauseSec.toString()) }
        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.settings_weight)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxOverride,
            onValueChange = { maxOverride = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.settings_max_override)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = factor,
            onValueChange = { factor = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.settings_calorie_factor)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = autoPause,
            onValueChange = { autoPause = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.settings_auto_pause)) },
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
        }) { Text(stringResource(R.string.action_save)) }
        HorizontalDivider()

        var reconnect by remember { mutableStateOf(s.autoReconnect) }
        SwitchRow(stringResource(R.string.settings_auto_reconnect), reconnect) { reconnect = it; s.autoReconnect = it }
        var verbose by remember { mutableStateOf(controller.verboseNotify) }
        SwitchRow(stringResource(R.string.settings_verbose), verbose) { verbose = it; controller.verboseNotify = it }
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
    SectionCard(stringResource(R.string.card_debug)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand))
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n"))) }) {
                Text(stringResource(R.string.action_copy_log))
            }
            TextButton(onClick = { controller.clearLogs() }) { Text(stringResource(R.string.action_clear)) }
        }
        if (!expanded) {
            logs.lastOrNull()?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2) }
            return@SectionCard
        }
        if (connected) {
            var ch by remember { mutableStateOf("880f") }
            var hex by remember { mutableStateOf("") }
            var result by remember { mutableStateOf<String?>(null) }
            Text(stringResource(R.string.debug_raw_title), fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ch,
                    onValueChange = { ch = it.trim() },
                    label = { Text(stringResource(R.string.debug_characteristic)) },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text(stringResource(R.string.debug_hex_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            val queued = stringResource(R.string.debug_queued)
            Button(onClick = { result = controller.sendRaw(ch, hex) ?: queued }) { Text(stringResource(R.string.action_send)) }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
        }
        Spacer(Modifier.height(4.dp))
        logs.takeLast(150).asReversed().forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
