package com.youjixingtu.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.youjixingtu.app.data.Repository

/**
 * v1.18：开机后恢复蓝牙监听保活服务。
 * 用户若已开启「自动打开 + 后台保活」，开机无需手动打开 App 也能继续监听车机蓝牙。
 */
class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Repository.init(context.applicationContext)
        if (!Repository.btUseOwnSolution) return
        if (!Repository.btKeepAlive || !Repository.btAutoStartEnabled) return
        runCatching { BluetoothWatchService.start(context.applicationContext) }
    }
}
