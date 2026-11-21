package com.youjixingtu.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.amap.api.location.AMapLocationClient
import com.amap.api.services.core.ServiceSettings
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.util.BluetoothWatchService
import com.youjixingtu.app.util.InsetsUtil

class YouJiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repository.init(this)

        // v1.18：若用户已开启蓝牙自动启动 + 后台保活，进程一起来就拉起前台服务，
        // 避免被系统冻结导致收不到蓝牙广播（含手机重启后被广播拉起的场景）。
        if (Repository.btUseOwnSolution && Repository.btKeepAlive && Repository.btAutoStartEnabled) {
            BluetoothWatchService.start(this)
        }

        // 高德 6.x/9.x 强制要求：任何业务接口（含构造客户端）之前必须完成隐私合规声明。
        // 漏掉这一步时，new AMapLocationClient() 会直接抛异常，表现为「定位不可用 /
        // 确保调用 updatePrivacyShow、updatePrivacyAgree」。这两步必须先于 setApiKey。
        applyAmapPrivacy(this)

        // 高德要求：Key 必须在任何 SDK 业务初始化/调用之前设置。
        // 放在 Application 起始（比任何 Activity 都早），确保定位和搜索 SDK 首次使用时读到的是有效 key，
        // 而不是 Manifest 里的空占位符。App 内设置的 key 保存在 Repository.amapKey。
        val key = Repository.amapKey
        if (key.isNotBlank()) {
            runCatching { AMapLocationClient.setApiKey(key) }
            runCatching { ServiceSettings.getInstance().setApiKey(key) }
        }

        // 统一给每个页面加系统栏 padding。真机（尤其国产 ROM / Android 15+）会忽略
        // windowOptOutEdgeToEdgeEnforcement，导致内容顶进状态栏、底部按钮被手势条截断。
        // 这里在每页 content 视图上直接读真实 insets 加 padding，保证不留空白也不重叠。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.findViewById<android.view.View>(android.R.id.content)?.let { content ->
                    // setContentView 通常在 onCreate 里稍后执行，post 到下一帧确保根视图已就位
                    content.post { InsetsUtil.apply(activity, content) }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        /**
         * 高德隐私合规：定位与搜索 SDK 各有一套，必须都调用，且早于 key 注入与客户端构造。
         * 参数含义：隐私条款已包含、已展示、用户已同意（本 App 有隐私政策且用户同意后使用）。
         */
        fun applyAmapPrivacy(context: Context) {
            runCatching { AMapLocationClient.updatePrivacyShow(context, true, true) }
            runCatching { AMapLocationClient.updatePrivacyAgree(context, true) }
            runCatching { ServiceSettings.updatePrivacyShow(context, true, true) }
            runCatching { ServiceSettings.updatePrivacyAgree(context, true) }
        }
    }
}
