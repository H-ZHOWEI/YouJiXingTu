package com.youjixingtu.app.util

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
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
 * v1.17：车载蓝牙自动启动（可诊断版）。
 *
 * 监听三类广播：ACL 连接、A2DP 媒体音频连接、HFP 免提连接——车机大多是后两者才真正「连上」，
 * 只听 ACL 时容易错过。判定过程全部写入 [Repository.btLastEvent]，在卡片上可见，便于自查。
 *
 * 匹配优先级：MAC 地址 > 蓝牙名。Android 12+ 广播里读设备名需要 BLUETOOTH_CONNECT，
 * 且很多车机在 ACL 广播时名字尚未就绪；MAC 地址稳定可靠，故「从已配对设备选择」会同时存 MAC。
 */
class BluetoothAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // A2DP/HFP 广播每次连接状态变化都会来，只在「已连接」时处理，避免断开/连接中误触发
        if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ||
            action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
        ) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (state != BluetoothProfile.STATE_CONNECTED) return
        }
        val device = getDeviceExtra(intent) ?: run {
            record("收到「${actionShort(action)}」广播，但系统未附带设备信息")
            return
        }

        if (!Repository.btAutoStartEnabled) {
            record("收到「${actionShort(action)}」广播，但自动打开开关未开启")
            return
        }
        val configuredName = Repository.btDeviceName.trim()
        val configuredAddr = Repository.btDeviceAddress.trim()
        if (configuredName.isEmpty() && configuredAddr.isEmpty()) {
            record("收到蓝牙连接，但未配置车机设备")
            return
        }

        val hasBtPerm = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        val name = if (hasBtPerm) runCatching { device.name }.getOrNull().orEmpty().trim() else ""
        val addr = if (hasBtPerm) runCatching { device.address }.getOrNull().orEmpty().trim() else ""

        // MAC 优先，名字兜底（双向包含、忽略大小写）
        val addrMatch = configuredAddr.isNotEmpty() && addr.equals(configuredAddr, ignoreCase = true)
        val nameMatch = name.isNotEmpty() && configuredName.isNotEmpty() &&
            (name.contains(configuredName, ignoreCase = true) ||
                configuredName.contains(name, ignoreCase = true))
        if (!addrMatch && !nameMatch) {
            val dev = name.ifBlank { "未命名设备" }
            val cfg = configuredName.ifBlank { "" } + if (configuredAddr.isNotEmpty()) " / ${maskAddr(configuredAddr)}" else ""
            record("连接了「$dev」${if (addr.isNotEmpty()) " [${maskAddr(addr)}]" else ""}，与配置「$cfg」不匹配")
            return
        }

        val overlayGranted = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val started = runCatching { context.startActivity(launch) }.isSuccess
        record(
            "连接「${name.ifBlank { maskAddr(addr) }}」匹配成功（${if (addrMatch) "MAC" else "名称"}）→ " +
                if (started) {
                    "已发起自动打开" + if (!overlayGranted) "；悬浮窗未授权，可能被系统拦截" else ""
                } else {
                    "启动被系统拒绝；请检查悬浮窗权限与 ROM 自启动设置"
                }
        )
    }

    private fun record(msg: String) {
        runCatching { Repository.btLastEvent = msg }
    }

    private fun getDeviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun actionShort(action: String) = when (action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> "蓝牙连接"
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "媒体音频连接"
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "免提连接"
        else -> "蓝牙广播"
    }

    private fun maskAddr(addr: String): String =
        if (addr.length >= 8) addr.takeLast(8) else addr

    companion object {
        /**
         * v1.17：检查自动打开的前置条件是否齐备，返回人类可读结论。
         * 供卡片「测试配置」按钮调用——不用真去车里连蓝牙也能知道卡在哪一步。
         */
        fun diagnose(context: Context): String {
            val problems = mutableListOf<String>()
            if (!Repository.btAutoStartEnabled) problems.add("①「启用」开关未勾选")
            val name = Repository.btDeviceName.trim()
            val addr = Repository.btDeviceAddress.trim()
            if (name.isEmpty() && addr.isEmpty()) problems.add("②未选择车机蓝牙（点「选择设备」）")
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
            ) problems.add("③蓝牙权限未授予（允许「附近的设备」）")
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)) {
                problems.add("④悬浮窗权限未授予（点「授权悬浮窗」）")
            }
            return if (problems.isEmpty()) {
                "配置齐全：开关 / 设备 / 蓝牙权限 / 悬浮窗都已就绪。上车连上该蓝牙即可自动打开；" +
                    "若仍不生效，请到 设置 → 应用管理 → 油迹星图 允许「自启动 / 后台运行」。"
            } else {
                "还差这些步骤：\n" + problems.joinToString("\n") + "\n补齐后再连接蓝牙测试。"
            }
        }
    }
}
