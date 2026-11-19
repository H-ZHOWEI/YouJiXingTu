package com.youjixingtu.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.youjixingtu.app.R
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.ActivityMainBinding
import com.youjixingtu.app.util.UpdateManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recordFragment: RecordFragment
    private lateinit var statsFragment: StatsFragment
    private lateinit var stationsFragment: StationsFragment
    private lateinit var historyFragment: HistoryFragment
    private lateinit var currentFragment: Fragment
    private var isChecking = false
    private var isDownloading = false
    private var pendingInfo: UpdateManager.Info? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "v${UpdateManager.currentVersion(this)}"

        recordFragment = supportFragmentManager.findFragmentByTag("record") as? RecordFragment ?: RecordFragment()
        statsFragment = supportFragmentManager.findFragmentByTag("stats") as? StatsFragment ?: StatsFragment()
        stationsFragment = supportFragmentManager.findFragmentByTag("stations") as? StationsFragment ?: StationsFragment()
        historyFragment = supportFragmentManager.findFragmentByTag("history") as? HistoryFragment ?: HistoryFragment()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, recordFragment, "record")
                .add(R.id.fragmentContainer, statsFragment, "stats")
                .hide(statsFragment)
                .add(R.id.fragmentContainer, stationsFragment, "stations")
                .hide(stationsFragment)
                .add(R.id.fragmentContainer, historyFragment, "history")
                .hide(historyFragment)
                .commit()
        }
        currentFragment = when {
            historyFragment.isVisible -> historyFragment
            stationsFragment.isVisible -> stationsFragment
            statsFragment.isVisible -> statsFragment
            else -> recordFragment
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_stats -> statsFragment
                R.id.nav_stations -> stationsFragment
                R.id.nav_history -> historyFragment
                else -> recordFragment
            }
            switchFragment(target)
            true
        }

        binding.checkUpdateBtn.setOnClickListener { manualCheck() }
        binding.dismissUpdateBtn.setOnClickListener { binding.updateBanner.visibility = View.GONE }
        binding.skipVersionBtn.setOnClickListener {
            pendingInfo?.let {
                UpdateManager.skipVersion(this, it.version)
                binding.updateBanner.visibility = View.GONE
                toast("已跳过 v${it.version}，下次启动不再提示")
            }
        }
        binding.updateNowBtn.setOnClickListener {
            pendingInfo?.let { startUpdate(it) }
        }

        // I8：低油量横幅按钮
        binding.lowFuelGoBtn.setOnClickListener {
            binding.lowFuelBanner.visibility = View.GONE
            recordFragment.openNearbyStation()
        }
        binding.lowFuelDismissBtn.setOnClickListener {
            Repository.lowFuelNoticeDay = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            binding.lowFuelBanner.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        binding.starField.resumeAnimation() // D1
        silentCheck()
        checkLowFuel()
    }

    /** I8：低油量主动提醒，每天最多一次 */
    private fun checkLowFuel() {
        val records = Repository.loadRecords()
        if (records.isEmpty()) return
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 }
            ?: Analytics.estimateTankCapacity(records)
        val remaining = Analytics.calculateRemainingFuel(records, capacity) ?: return
        val pct = remaining / capacity * 100
        if (pct > 15) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (Repository.lowFuelNoticeDay == today) return
        val km = Analytics.predictNextRefuel(records, capacity)?.km
        binding.lowFuelBannerText.text = if (km != null)
            "⚠ 油量仅剩约 ${pct.toInt()}%，预计还能跑 $km 公里，建议尽快加油"
        else "⚠ 油量仅剩约 ${pct.toInt()}%，建议尽快加油"
        binding.lowFuelBanner.visibility = View.VISIBLE
    }

    /** I12：切换到「星迹」页 */
    fun switchToHistory() {
        binding.bottomNav.selectedItemId = R.id.nav_history
    }

    /** I8：切换到「记录」页（供记录页一键定位到自身） */
    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    override fun onPause() {
        binding.starField.pauseAnimation() // D1：后台暂停星空动画
        super.onPause()
    }

    private fun switchFragment(target: Fragment) {
        if (target === currentFragment) return
        supportFragmentManager.beginTransaction().hide(currentFragment).show(target).commit()
        currentFragment = target
        when (target) {
            is StatsFragment -> target.refresh()
            is StationsFragment -> target.refresh()
            is HistoryFragment -> target.refresh()
            is RecordFragment -> target.refresh()
        }
    }

    private fun silentCheck() {
        if (isChecking || binding.updateBanner.visibility == View.VISIBLE) return
        isChecking = true
        lifecycleScope.launch {
            val result = UpdateManager.checkUpdateDetailed(this@MainActivity, attempts = 3)
            isChecking = false
            if (result is UpdateManager.CheckResult.NewVersion && !UpdateManager.isSkipped(this@MainActivity, result.info.version)) {
                showUpdateBanner(result.info, fromStartup = true)
            }
            // 启动静默检查：已是最新或失败都不打扰用户
        }
    }

    private fun manualCheck() {
        if (isChecking) {
            toast("正在检查中，请稍候")
            return
        }
        isChecking = true
        toast("正在检查更新…")
        lifecycleScope.launch {
            val result = UpdateManager.checkUpdateDetailed(this@MainActivity, attempts = 2)
            isChecking = false
            when (result) {
                is UpdateManager.CheckResult.NewVersion -> {
                    if (UpdateManager.isSkipped(this@MainActivity, result.info.version)) {
                        toast("已是最新版本（已跳过 v${result.info.version}）")
                    } else {
                        showUpdateBanner(result.info, fromStartup = false)
                    }
                }
                is UpdateManager.CheckResult.UpToDate -> toast("已是最新版本")
                is UpdateManager.CheckResult.Failed -> toast("检查更新失败：${result.reason}")
            }
        }
    }

    private fun showUpdateBanner(info: UpdateManager.Info, fromStartup: Boolean) {
        pendingInfo = info
        binding.updateBanner.visibility = View.VISIBLE
        binding.skipVersionBtn.visibility = if (fromStartup) View.VISIBLE else View.GONE
        // I14：只显示当前版本那一段说明（从整篇 CHANGELOG 中截取），不再截头去尾显示一堆标题
        val note = UpdateManager.extractVersionNotes(info.notes, info.version)
            .takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        binding.updateBannerText.text = "发现新版本 v${info.version}$note"
    }

    private fun startUpdate(info: UpdateManager.Info) {
        if (isDownloading) {
            toast("正在下载中，请稍候")
            return
        }
        if (!UpdateManager.canInstall(this)) {
            toast("请先允许安装未知应用，然后再次点击更新", long = true)
            UpdateManager.openInstallPermission(this)
            return
        }
        isDownloading = true
        binding.updateNowBtn.isEnabled = false
        binding.updateNowBtn.text = "下载中…"
        binding.updateBannerText.text = "正在下载 v${info.version} · 准备中… 0%"
        lifecycleScope.launch {
            var shownPercent = 0
            fun render() {
                val s = UpdateManager.status.value
                binding.updateBannerText.text =
                    "正在下载 v${info.version}" + (if (s.isNotBlank()) " · $s" else "") + " · ${shownPercent}%"
            }
            val progressJob = launch {
                UpdateManager.progress.collect { p ->
                    if (p >= 0f) {
                        val percent = (p * 100).toInt()
                        // UI 层再钳制一次：进度只增不减，杜绝百分比回跳
                        if (percent > shownPercent) shownPercent = percent
                        render()
                    }
                }
            }
            val statusJob = launch {
                UpdateManager.status.collect { s ->
                    if (s.isNotBlank() && UpdateManager.progress.value >= 0f) render()
                }
            }
            val apk = UpdateManager.download(this@MainActivity, info)
            progressJob.cancel()
            statusJob.cancel()
            isDownloading = false
            binding.updateNowBtn.isEnabled = true
            if (apk == null) {
                // 失败后保留横幅与按钮，用户可直接点「重新下载」续传，不用重新检查更新
                binding.updateBannerText.text = "下载失败：网络不稳定，可点「重新下载」继续（已下载部分会保留）"
                binding.updateNowBtn.text = "重新下载"
                toast("下载失败，请重试", long = true)
            } else {
                binding.updateBannerText.text = "下载完成，正在打开安装界面…"
                binding.updateNowBtn.text = "立即更新"
                if (!UpdateManager.install(this@MainActivity, apk)) {
                    toast("无法拉起安装，请检查安装权限")
                }
            }
        }
    }
}
