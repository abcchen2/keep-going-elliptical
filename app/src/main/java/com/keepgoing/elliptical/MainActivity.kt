package com.keepgoing.elliptical

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.keepgoing.elliptical.ui.MainScreen
import com.keepgoing.elliptical.ui.KeepGoingTheme

class MainActivity : ComponentActivity() {

    private val controller get() = (application as KeepGoingApp).controller

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                ensureBluetoothOn()
            } else {
                afterPermission = null
                Toast.makeText(this, "需要蓝牙权限才能连接椭圆机", Toast.LENGTH_LONG).show()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (controller.isBluetoothOn) runPending() else afterPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeepGoingTheme {
                MainScreen(
                    controller = controller,
                    withBluetooth = { action -> withBluetooth(action) },
                )
            }
        }
    }

    /** Runs [action] once permissions are granted and the adapter is on. */
    private fun withBluetooth(action: () -> Unit) {
        afterPermission = action
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            ensureBluetoothOn()
        }
    }

    private fun ensureBluetoothOn() {
        if (controller.isBluetoothOn) {
            runPending()
        } else {
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                Toast.makeText(this, "请先在系统设置中打开蓝牙", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runPending() {
        val a = afterPermission
        afterPermission = null
        a?.invoke()
    }
}
