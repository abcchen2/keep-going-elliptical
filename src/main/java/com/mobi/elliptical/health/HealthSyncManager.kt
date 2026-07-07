package com.mobi.elliptical.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.mobi.elliptical.data.ExerciseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health Connect 数据同步管理器（暂时禁用 - API 版本兼容性问题）
 */
@Singleton
class HealthSyncManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 暂时禁用 Health Connect 同步功能
    // TODO: 修复 Health Connect API 版本兼容性问题
    
    /**
     * 初始化（暂时返回 false）
     */
    fun initialize(): Boolean {
        return false
    }
    
    /**
     * 检查 Health Connect 是否已安装
     */
    fun isHealthConnectAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.google.android.healthconnect", 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取安装 Health Connect 的 Intent
     */
    fun getInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=com.google.android.healthconnect")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    
    /**
     * 检查是否已授予所有权限（暂时返回 false）
     */
    suspend fun hasAllPermissions(): Boolean {
        return false
    }
    
    /**
     * 获取权限请求 Intent（暂时返回空 Intent）
     */
    fun getPermissionRequestIntent(): Intent {
        return Intent()
    }
    
    /**
     * 同步运动数据（暂时返回 false）
     */
    suspend fun syncExerciseData(session: ExerciseSession): Boolean {
        return false
    }
    
    /**
     * 获取 Health Connect 设置 Intent
     */
    fun getSettingsIntent(): Intent {
        return Intent().apply {
            action = "android.health.connect.action.HEALTH_CONNECT_SETTINGS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
