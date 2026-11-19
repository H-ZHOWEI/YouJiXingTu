package com.youjixingtu.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.youjixingtu.app.R
import com.youjixingtu.app.databinding.ActivityMainBinding
import com.youjixingtu.app.util.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recordFragment: RecordFragment
    private lateinit var statsFragment: StatsFragment
    private lateinit var stationsFragment: StationsFragment
    private lateinit var historyFragment: HistoryFragment
    private lateinit var currentFragment: Fragment
    private var isChecking = false
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
    }

    override fun onResume() {
        super.onResume()
        binding.starField.resumeAnimation() // D1
        silentCheck()
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
        // C1：更新说明可能是一整段 CHANGELOG，截断避免撑爆横幅
        val note = info.notes.takeIf { it.isNotBlank() }?.let {
            val trimmed = it.replace('\r', ' ').replace('\n', ' ')
            val short = if (trimmed.length > 80) trimmed.take(80) + "…" else trimmed
            "\n$short"
        }.orEmpty()
        binding.updateBannerText.text = "发现新版本 v${info.version}$note"
    }

    private fun startUpdate(info: UpdateManager.Info) {
        if (!UpdateManager.canInstall(this)) {
            toast("请先允许安装未知应用，然后再次点击更新", long = true)
            UpdateManager.openInstallPermission(this)
            return
        }
        binding.updateBannerText.text = "正在下载 v${info.version}… 0%"
        lifecycleScope.launch {
            var progressJob: Job? = launch {
                UpdateManager.progress.collect { p ->
                    if (p >= 0f) {
                        binding.updateBannerText.text = "正在下载 v${info.version}… ${(p * 100).toInt()}%"
                    }
                }
            }
            val apk = UpdateManager.download(this@MainActivity, info)
            progressJob?.cancel()
            progressJob = null
            if (apk == null) {
                binding.updateBannerText.text = "下载失败，请检查网络后重试"
                toast("下载失败，请重试")
            } else {
                binding.updateBannerText.text = "下载完成，正在打开安装界面…"
                if (!UpdateManager.install(this@MainActivity, apk)) {
                    toast("无法拉起安装，请检查安装权限")
                }
            }
        }
    }
}
