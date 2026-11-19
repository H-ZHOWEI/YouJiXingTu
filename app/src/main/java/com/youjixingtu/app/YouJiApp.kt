package com.youjixingtu.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.amap.api.location.AMapLocationClient
import com.amap.api.services.core.ServiceSettings
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.util.InsetsUtil

class YouJiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repository.init(this)

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
}
