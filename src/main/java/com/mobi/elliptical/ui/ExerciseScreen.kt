package com.mobi.elliptical.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobi.elliptical.R
import com.mobi.elliptical.data.ConnectionState
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 运动主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(viewModel: EllipticalViewModel = viewModel()) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val exerciseData by viewModel.exerciseData.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("莫比椭圆机") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 连接状态
            ConnectionStatus(connectionState = connectionState)

            Spacer(modifier = Modifier.height(16.dp))

            if (connectionState is ConnectionState.Connected) {
                // 运动数据展示
                ExerciseTimer(
                    duration = exerciseData.duration,
                    isRunning = exerciseData.isRunning
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 数据卡片
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DataCard(
                        title = "踏频",
                        value = "${exerciseData.cadence}",
                        unit = "步/分",
                        iconRes = R.drawable.ic_cadence
                    )
                    DataCard(
                        title = "心率",
                        value = if (exerciseData.heartRate > 0) "${exerciseData.heartRate}" else "--",
                        unit = "bpm",
                        iconRes = R.drawable.ic_heart
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DataCard(
                        title = "热量",
                        value = String.format("%.1f", exerciseData.calories),
                        unit = "千卡",
                        iconRes = R.drawable.ic_calories
                    )
                    DataCard(
                        title = "距离",
                        value = String.format("%.2f", exerciseData.distance),
                        unit = "公里",
                        iconRes = R.drawable.ic_distance
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DataCard(
                        title = "功率",
                        value = String.format("%.0f", exerciseData.power),
                        unit = "瓦特",
                        iconRes = R.drawable.ic_power
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 阻力调节
                val resistanceRange by viewModel.resistanceRange.collectAsStateWithLifecycle()
                ResistanceControl(
                    currentResistance = exerciseData.resistance,
                    minResistance = resistanceRange.first,
                    maxResistance = resistanceRange.second,
                    onResistanceChange = { viewModel.setResistance(it) }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 控制按钮
                ExerciseControls(
                    isExercising = uiState.isExercising,
                    onStart = { viewModel.startExercise() },
                    onStop = { viewModel.stopExercise() },
                    onReset = { viewModel.resetExercise() }
                )
            }
        }
    }
}

/**
 * 运动计时器
 */
@Composable
fun ExerciseTimer(duration: Long, isRunning: Boolean) {
    val hours = TimeUnit.MILLISECONDS.toHours(duration)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60

    val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = timeString,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRunning) MaterialTheme.colorScheme.primary else Color.Gray
        )
        Text(
            text = if (isRunning) "运动中..." else "已暂停",
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

/**
 * 数据卡片
 */
@Composable
fun DataCard(title: String, value: String, unit: String, iconRes: Int) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .height(120.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = unit,
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
}

/**
 * 阻力控制
 *
 * 关键设计：滑块、按钮、显示数字三者使用同一状态源（sliderValueState），
 * 避免设备反馈延迟导致的"滑块到最右边但+按钮仍可点"问题。
 *
 * - 拖动滑块时：只更新本地 sliderValueState，松开时才发送命令
 * - 点击 +/- 按钮时：立即更新 sliderValueState 并发送命令
 * - 设备反馈更新 currentResistance 时：非拖动状态下同步到 sliderValueState
 */
@Composable
fun ResistanceControl(
    currentResistance: Int,
    minResistance: Int = 1,
    maxResistance: Int = 24,
    onResistanceChange: (Int) -> Unit
) {
    // 防御性处理：确保 min < max，且都在合理范围
    val safeMin = minResistance.coerceIn(1, 32)
    val safeMax = if (maxResistance in (safeMin + 1)..32) maxResistance else 24
    val safeCurrent = currentResistance.coerceIn(safeMin, safeMax)

    // 滑块的浮点值 — 唯一的真相源
    val sliderValueState = remember { mutableFloatStateOf(safeCurrent.toFloat()) }
    val isDraggingState = remember { mutableStateOf(false) }

    // 设备反馈更新时，仅在非拖动状态下同步到滑块
    LaunchedEffect(safeCurrent) {
        if (!isDraggingState.value) {
            sliderValueState.floatValue = safeCurrent.toFloat()
        }
    }

    // 当前显示/操作的阻力值（基于滑块位置，四舍五入）
    val displayValue = sliderValueState.floatValue.roundToInt().coerceIn(safeMin, safeMax)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "阻力调节",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // "-" 按钮：基于 displayValue 判断，到最小值时禁用
            Button(
                onClick = {
                    val newValue = (displayValue - 1).coerceAtLeast(safeMin)
                    if (newValue != displayValue) {
                        sliderValueState.floatValue = newValue.toFloat()
                        onResistanceChange(newValue)
                    }
                },
                enabled = displayValue > safeMin,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                )
            ) {
                Text("-", fontSize = 24.sp, color = Color.White)
            }

            // 显示数字 — 基于 displayValue，和滑块完全同步
            Text(
                text = "$displayValue",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            // "+" 按钮：基于 displayValue 判断，到最大值时禁用
            Button(
                onClick = {
                    val newValue = (displayValue + 1).coerceAtMost(safeMax)
                    if (newValue != displayValue) {
                        sliderValueState.floatValue = newValue.toFloat()
                        onResistanceChange(newValue)
                    }
                },
                enabled = displayValue < safeMax,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                )
            ) {
                Text("+", fontSize = 24.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 阻力滑块
        Slider(
            value = sliderValueState.floatValue.coerceIn(safeMin.toFloat(), safeMax.toFloat()),
            onValueChange = { newValue ->
                isDraggingState.value = true
                sliderValueState.floatValue = newValue
            },
            onValueChangeFinished = {
                isDraggingState.value = false
                // 松开时四舍五入到整数，同步滑块位置并发送命令
                val finalValue = sliderValueState.floatValue.roundToInt().coerceIn(safeMin, safeMax)
                sliderValueState.floatValue = finalValue.toFloat()
                onResistanceChange(finalValue)
            },
            valueRange = safeMin.toFloat()..safeMax.toFloat(),
            steps = if (safeMax - safeMin > 1) safeMax - safeMin - 1 else 0,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        // 范围提示
        Text(
            text = "$safeMin - $safeMax",
            fontSize = 11.sp,
            color = Color.Gray
        )
    }
}

/**
 * 运动控制按钮
 */
@Composable
fun ExerciseControls(
    isExercising: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        if (!isExercising) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier.size(width = 100.dp, height = 48.dp)
            ) {
                Text("开始", fontSize = 16.sp, color = Color.White)
            }
        } else {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                ),
                modifier = Modifier.size(width = 100.dp, height = 48.dp)
            ) {
                Text("停止", fontSize = 16.sp, color = Color.White)
            }
        }

        Button(
            onClick = onReset,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            ),
            modifier = Modifier.size(width = 100.dp, height = 48.dp)
        ) {
            Text("重置", fontSize = 16.sp, color = Color.White)
        }
    }
}

/**
 * 连接状态指示
 */
@Composable
fun ConnectionStatus(connectionState: ConnectionState) {
    val (text, color) = when (connectionState) {
        is ConnectionState.Disconnected -> "未连接" to Color.Gray
        is ConnectionState.Scanning -> "扫描中..." to Color(0xFFFFA000)
        is ConnectionState.Connecting -> "连接中..." to Color(0xFFFFA000)
        is ConnectionState.Connected -> "已连接" to Color(0xFF4CAF50)
        is ConnectionState.Error -> "错误: ${(connectionState as ConnectionState.Error).message}" to Color(0xFFF44336)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
