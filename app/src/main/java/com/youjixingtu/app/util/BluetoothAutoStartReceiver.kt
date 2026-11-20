package com.youjixingtu.app.util

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.ui.MainActivity

/**
 * v1.15：车载蓝牙自动启动。
 *
 * 监听系统「蓝牙设备已连接」广播（ACTION_ACL_CONNECTED），当连接的设备名
 * 与用户在记录页配置的蓝牙名称匹配时，自动打开本软件。
 *
 * 注意事项：
 * · Android 12+ 读取蓝牙设备名需要 BLUETOOTH_CONNECT 运行时权限（配置入口会引导授权）；
 * · Android 10+ 后台启动 Activity 需要「显示在其他应用上层」（悬浮窗）权限，
 *   配置入口提供一键跳转系统设置的按钮；
 * · 若被系统「强制停止」或 ROM 的自启动管理拦截，广播可能收不到——提示用户
 *   在系统设置里允许本软件自启动/后台运行。
 */
class BluetoothAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!Repository.btAutoStartEnabled) return
        val configured = Repository.btDeviceName.trim()
        if (configured.isEmpty()) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        device ?: return

        // Android 12+ 必须持有 BLUETOOTH_CONNECT 才能读设备名，未授权则跳过
        val name: String? = if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            runCatching { device.name }.getOrNull()
        }
        val deviceName = name?.trim().orEmpty()
        if (deviceName.isEmpty()) return

        // 双向包含匹配，宽容大小写：配置「蓝牙A」能匹配系统名「蓝牙A」或「XX蓝牙A」
        val matched = deviceName.contains(configured, ignoreCase = true) ||
            configured.contains(deviceName, ignoreCase = true)
        if (!matched) return

        // Android 10+ 后台启动 Activity 需要悬浮窗权限，未授权则跳过
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)) return

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(launch) }
    }
}
