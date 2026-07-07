package com.mobi.elliptical.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobi.elliptical.data.ConnectionState
import com.mobi.elliptical.data.ExerciseData
import com.mobi.elliptical.ble.EllipticalBleManager
import com.mobi.elliptical.health.HealthSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 主界面 ViewModel
 */
@HiltViewModel
class EllipticalViewModel @Inject constructor(
    private val bleManager: EllipticalBleManager,
    private val healthSyncManager: HealthSyncManager
) : ViewModel() {

    // UI 状态
    private val _uiState = MutableStateFlow(EllipticalUiState())
    val uiState: StateFlow<EllipticalUiState> = _uiState.asStateFlow()

    // 连接状态
    val connectionState = bleManager.connectionState

    // 运动数据
    val exerciseData = bleManager.exerciseData

    // 阻力范围
    val resistanceRange = bleManager.resistanceRange

    init {
        // 初始化 Health Connect
        healthSyncManager.initialize()

        // 观察连接状态变化
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        // 观察运动数据变化
        viewModelScope.launch {
            bleManager.exerciseData.collect { data ->
                _uiState.update { it.copy(exerciseData = data) }
            }
        }
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        viewModelScope.launch {
            bleManager.scanForDevices().collect { devices ->
                _uiState.update { it.copy(scannedDevices = devices) }
            }
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        // 扫描会在flow关闭时自动停止
    }

    /**
     * 连接设备
     */
    fun connectDevice(device: android.bluetooth.BluetoothDevice) {
        bleManager.connectToDevice(device)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        bleManager.disconnect()
    }

    /**
     * 设置阻力
     */
    fun setResistance(level: Int) {
        bleManager.setResistance(level)
    }

    /**
     * 开始运动
     */
    fun startExercise() {
        bleManager.startExercise()
        _uiState.update { it.copy(isExercising = true) }
    }

    /**
     * 停止运动
     */
    fun stopExercise() {
        bleManager.stopExercise()
        _uiState.update { it.copy(isExercising = false) }
    }

    /**
     * 重置运动数据
     */
    fun resetExercise() {
        bleManager.resetExerciseData()
        _uiState.update { it.copy(isExercising = false) }
    }

    /**
     * 检查 Health Connect 权限
     */
    suspend fun checkHealthConnectPermissions(): Boolean {
        return healthSyncManager.hasAllPermissions()
    }

    /**
     * 获取 Health Connect 权限请求 Intent
     */
    fun getHealthConnectPermissionIntent(): android.content.Intent {
        return healthSyncManager.getPermissionRequestIntent()
    }

    /**
     * 同步运动数据到 Health Connect
     */
    fun syncToHealthConnect(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val exerciseData = _uiState.value.exerciseData
            val session = com.mobi.elliptical.data.ExerciseSession(
                startTime = System.currentTimeMillis() - exerciseData.duration,
                endTime = System.currentTimeMillis(),
                totalCalories = exerciseData.calories,
                totalDistance = exerciseData.distance,
                totalSteps = exerciseData.strideCount,
                avgCadence = if (exerciseData.cadence > 0) exerciseData.cadence else 0,
                avgPower = exerciseData.power
            )
            val result = healthSyncManager.syncExerciseData(session)
            onResult(result)
        }
    }

    /**
     * 检查 Health Connect 是否可用
     */
    fun isHealthConnectAvailable(): Boolean {
        return healthSyncManager.isHealthConnectAvailable()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.release()
    }
}

/**
 * UI 状态
 */
data class EllipticalUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val exerciseData: ExerciseData = ExerciseData(),
    val scannedDevices: List<android.bluetooth.BluetoothDevice> = emptyList(),
    val isExercising: Boolean = false,
    val isScanning: Boolean = false
)
