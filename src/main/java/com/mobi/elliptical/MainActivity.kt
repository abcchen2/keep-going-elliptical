package com.mobi.elliptical

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mobi.elliptical.ui.DeviceScanScreen
import com.mobi.elliptical.ui.EllipticalViewModel
import com.mobi.elliptical.ui.ExerciseScreen
import com.mobi.elliptical.ui.theme.MobiEllipticalTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: EllipticalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobiEllipticalTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * 获取当前 Android 版本需要请求的 BLE 权限列表（非 Composable 函数）
 */
fun getRequiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    // Android 12+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    // Android 6-11: 位置权限（BLE 扫描需要）
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
} else {
    emptyArray()
}

@Composable
fun MainScreen(viewModel: EllipticalViewModel) {
    val navController = rememberNavController()

    // 权限状态：null = 未检查/等待中, true = 已授权, false = 已拒绝
    var permissionGranted by remember { mutableStateOf<Boolean?>(null) }

    // 权限请求 launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result.values.all { it }
    }

    // 首次进入时自动请求权限
    val permissions = remember { getRequiredBlePermissions() }
    LaunchedEffect(Unit) {
        if (permissions.isEmpty()) {
            // 低版本不需要运行时权限
            permissionGranted = true
        } else {
            // 发起权限请求
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "permission_gate",
            modifier = Modifier.padding(paddingValues)
        ) {
            // 权限守卫页
            composable("permission_gate") {
                when (permissionGranted) {
                    true -> {
                        // 已授权 → 直接跳转到设备扫描
                        LaunchedEffect(Unit) {
                            navController.navigate("device_scan") {
                                popUpTo("permission_gate") { inclusive = true }
                            }
                        }
                    }
                    false -> {
                        // 被拒绝 → 显示说明页面，提供重试按钮
                        PermissionDeniedScreen(onRetry = {
                            permissionLauncher.launch(permissions)
                        })
                    }
                    null -> {
                        // 正在等待用户响应授权弹窗
                        PermissionRequestScreen()
                    }
                }
            }

            // 设备扫描页
            composable("device_scan") {
                DeviceScanScreen(
                    viewModel = viewModel,
                    onDeviceConnected = {
                        navController.navigate("exercise") {
                            popUpTo("device_scan") { saveState = false }
                        }
                    }
                )
            }

            // 运动页面
            composable("exercise") {
                ExerciseScreen(viewModel = viewModel)
            }
        }
    }
}

/** 正在等待用户授权蓝牙权限 */
@Composable
fun PermissionRequestScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "\uD83D\uDC99",
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "莫比椭圆机",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "正在请求蓝牙权限...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请在弹出的窗口中点击\"允许\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 用户拒绝了蓝牙权限 */
@Composable
fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "\u26A0\uFE0F",
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "需要蓝牙权限",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "本应用需要蓝牙扫描和连接权限才能连接到莫比椭圆机。" +
                    "请在弹窗中选择\"允许\"或\"在使用时允许\"。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onRetry) {
                Text("重新授权")
            }
        }
    }
}
