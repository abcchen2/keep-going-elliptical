package com.mobi.elliptical.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobi.elliptical.R
import com.mobi.elliptical.data.ConnectionState

/**
 * 设备扫描屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(
    viewModel: EllipticalViewModel,
    onDeviceConnected: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    // 检查是否已连接
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onDeviceConnected()
        }
    }

    // 自动开始扫描
    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接莫比椭圆机") },
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
            // 扫描状态
            when (val state = connectionState) {
                is ConnectionState.Scanning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在搜索椭圆机...", style = MaterialTheme.typography.bodyLarge)
                }
                is ConnectionState.Connecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在连接...", style = MaterialTheme.typography.bodyLarge)
                }
                is ConnectionState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.startScan() }) {
                        Text("重新扫描")
                    }
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 设备列表
            Text(
                text = "发现的设备",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.scannedDevices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到设备\n请确认椭圆机已开机并在附近",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.scannedDevices) { device ->
                        DeviceItem(
                            deviceName = device.name ?: "Unknown Device",
                            deviceAddress = device.address,
                            onClick = { viewModel.connectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 设备列表项
 */
@Composable
fun DeviceItem(
    deviceName: String,
    deviceAddress: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = deviceName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = deviceAddress,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Button(
                onClick = onClick,
                content = { Text("连接") }
            )
        }
    }
}
