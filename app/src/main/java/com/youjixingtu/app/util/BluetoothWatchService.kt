package com.youjixingtu.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.youjixingtu.app.R
import com.youjixingtu.app.ui.MainActivity

/**
 * v1.18：蓝牙监听前台服务。
 *
 * 背景：ColorOS / Android 12+ 会把后台 App 进程「冻结」（frozen state），
 * 静态广播接收器因此被系统 SKIPPED（日志：Skipping delivery ... due to frozen state），
 * 蓝牙连接广播根本送不到，自动打开自然失效。
 *
 * 解决：常驻一个前台服务（带一条低优先级、无声的通知），让进程始终处于活跃状态、
 * 不被冻结，蓝牙广播即可正常投递给 [BluetoothAutoStartReceiver] 并拉起 App。
 *
 * 注意（Android 14+ 前台服务限制）：
 * · startForeground 只能在 onCreate 调一次；onStartCommand 里重复调用会在后台重建时被拒
 *   （ForegroundServiceStartNotAllowedException）导致崩溃循环。
 * · 从后台（如 Application.onCreate / 开机广播）启动 FGS 会被系统拒绝，需包裹异常，
 *   并结合「先从 UI 前台启动一次」或「临时白名单」保证成功。
 */
class BluetoothWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** v1.19：动态注册的接收器实例（前台服务在跑时用它，绕过 manifest 静态接收器被冻结跳过的限制） */
    private var dynamicReceiver: BluetoothAutoStartReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // 只在创建时提升为前台服务；从 Activity 前台启动是合法的
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            isRunning = true
        } catch (e: Exception) {
            // 后台启动时可能被系统拒绝，不能让它崩溃（否则 START_STICKY 无限重启）
            isRunning = false
        }
        // v1.19 关键修复：静态 manifest 接收器在 ColorOS/Android 16 上即使进程未冻结也会被
        // 「Skipping delivery ... due to frozen state」跳过。改为在前台服务里动态注册——
        // 动态接收器随活跃进程投递，不受该限制，这是本功能在本机可靠的唯一方式。
        registerDynamicReceiver()
        // v1.19 第二个关键修复：即使广播收到、匹配成功，startActivity 仍被系统 BAL
        // （Background activity launch blocked）拦截，原因 callingUidHasNonAppVisibleWindow=false。
        // 光有悬浮窗「权限」不够，必须实际显示一个悬浮窗，系统才允许后台启动 Activity。
        // 这里显示一个 1x1 像素、完全透明、不可点击的窗口，用户看不见，只为满足豁免条件。
        ensureOverlayWindow()
    }

    /** 1x1 透明悬浮窗，仅用于让系统认定本应用有可见窗口，豁免后台启动 Activity 限制 */
    private var overlayView: View? = null

    private fun ensureOverlayWindow() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            Log.w("BT_WATCH", "无悬浮窗权限，无法创建豁免窗口")
            return
        }
        runCatching {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val view = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= 26) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 0f
            }
            wm.addView(view, lp)
            overlayView = view
            Log.i("BT_WATCH", "透明豁免窗口已显示")
        }.onFailure {
            Log.e("BT_WATCH", "透明豁免窗口创建失败", it)
        }
    }

    private fun registerDynamicReceiver() {
        if (dynamicReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        val receiver = BluetoothAutoStartReceiver()
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            dynamicReceiver = receiver
            Log.i("BT_WATCH", "动态蓝牙接收器已注册")
        }.onFailure {
            Log.e("BT_WATCH", "动态接收器注册失败", it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 不在这里重复 startForeground —— 后台重建时会被拒绝并抛异常
        if (!isRunning) {
            try {
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                isRunning = true
            } catch (_: Exception) {
                isRunning = false
            }
        }
        // 不用 START_STICKY：后台重建的前台服务会被 Android 14+ 拒绝（无法再 startForeground），
        // 反而造成反复重启。前台服务优先级高，正常不会被系统杀掉；真被杀时由下次打开 App / 开机广播恢复。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dynamicReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        dynamicReceiver = null
        overlayView?.let { v -> runCatching { (getSystemService(WindowManager::class.java))?.removeView(v) } }
        overlayView = null
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "蓝牙自动启动",
            NotificationManager.IMPORTANCE_LOW   // 无声、不弹窗、不打扰
        ).apply {
            description = "保持后台活跃，连接车机蓝牙时自动打开油迹星图"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("油迹星图 · 蓝牙监听中")
            .setContentText("连接车机蓝牙后将自动打开，点此进入")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bt_watch"
        private const val NOTIFICATION_ID = 2021
        @Volatile
        private var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, BluetoothWatchService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BluetoothWatchService::class.java)) }
        }
    }
}
