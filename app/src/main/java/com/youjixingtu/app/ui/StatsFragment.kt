package com.youjixingtu.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.FragmentStatsBinding

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private var period = "week"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.periodWeekBtn.setOnClickListener { selectPeriod("week") }
        binding.periodMonthBtn.setOnClickListener { selectPeriod("month") }
        binding.periodYearBtn.setOnClickListener { selectPeriod("year") }
        binding.periodAllBtn.setOnClickListener { selectPeriod("all") }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun refresh() {
        if (_binding == null) return
        val records = Repository.loadRecords()
        val stats = Analytics.periodStats(records, period)

        binding.periodDistanceText.text = if (stats.hasData) "${stats.distance} km" else "-- km"
        binding.periodCostText.text = if (stats.hasData) "${stats.cost.toInt()} 元" else "-- 元"
        binding.periodFuelText.text = if (stats.hasData) "${formatNumber(stats.fuel, 1)} 升" else "-- 升"
        binding.periodConsumptionText.text = if (stats.hasData) "${formatNumber(stats.consumption, 2)} L/100km" else "-- L/100km"
        binding.periodCostPerKmText.text = if (stats.hasData) "${formatNumber(stats.costPerKm, 2)} 元/km" else "-- 元/km"

        val quality = Analytics.assessDataQuality(records)
        binding.qualityText.text = when (quality) {
            "good" -> "✓ 数据质量良好"
            "fair" -> "! 数据质量一般"
            else -> "× 数据质量较差"
        }
        binding.qualityText.setTextColor(
            when (quality) {
                "good" -> resources.getColor(com.youjixingtu.app.R.color.good, null)
                "fair" -> resources.getColor(com.youjixingtu.app.R.color.average, null)
                else -> resources.getColor(com.youjixingtu.app.R.color.poor, null)
            }
        )

        val gapStats = Analytics.gapStats(records)
        if (period == "all" && gapStats != null) {
            val details = gapStats.gaps.take(3).joinToString("\n") { gap ->
                val start = Analytics.formatDateOnly(Analytics.parseDate(gap.startDate))
                val end = Analytics.formatDateOnly(Analytics.parseDate(gap.endDate))
                "• $start → $end（${gap.days}天）${if (gap.hasRefuelData) " [有加油记录]" else ""}"
            }
            val more = if (gapStats.gaps.size > 3) "\n… 还有 ${gapStats.gaps.size - 3} 个间隔" else ""
            binding.gapWarningText.text = "检测到 ${gapStats.gapCount} 个数据不连续时段（间隔超过30天），" +
                "包含 ${gapStats.recordCount} 次加油记录。\n$details$more\n" +
                "期间油费 ${formatNumber(gapStats.totalCost, 1)} 元，油量 ${formatNumber(gapStats.totalLiters, 1)} 升，" +
                "平均油价 ${formatNumber(gapStats.avgCostPerLiter, 2)} 元/升"
            binding.gapWarningText.visibility = View.VISIBLE
        } else {
            binding.gapWarningText.visibility = View.GONE
        }

        val habits = Analytics.refuelHabits(records)
        binding.avgRefuelAmountText.text = "${formatNumber(habits.avgAmount, 1)} 升"
        binding.refuelFrequencyText.text = "${formatNumber(habits.avgIntervalDays, 1)} 天"

        // I9：下次加油预测
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 }
            ?: Analytics.estimateTankCapacity(records)
        val prediction = Analytics.predictNextRefuel(records, capacity)
        binding.nextRefuelText.text = prediction?.let { "🔮 下次加油预测：${it.message}" }
            ?: "🔮 下次加油预测：数据不足，多记几次加油后可预测"

        // I11：异常油耗提示
        val anomaly = Analytics.anomalousConsumptionHint(records)
        binding.anomalyHintText.visibility = if (anomaly.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.anomalyHintText.text = anomaly.orEmpty()

        // I12：数据质量可操作建议（点击跳到星迹页补记/清理）
        val advice = Analytics.qualityAdvice(records)
        binding.qualityAdviceText.visibility = if (advice.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.qualityAdviceText.text = if (advice.isNullOrBlank()) "" else "$advice 👉 点此去处理"
        binding.qualityAdviceText.setOnClickListener {
            (requireActivity() as? MainActivity)?.switchToHistory()
        }
    }

    private fun selectPeriod(value: String) {
        period = value
        val buttons = listOf(
            binding.periodWeekBtn to "week",
            binding.periodMonthBtn to "month",
            binding.periodYearBtn to "year",
            binding.periodAllBtn to "all"
        )
        buttons.forEach { (button, key) ->
            val active = key == value
            button.setBackgroundResource(
                if (active) com.youjixingtu.app.R.drawable.bg_button_primary
                else com.youjixingtu.app.R.drawable.bg_button_ghost
            )
            button.setTextColor(
                resources.getColor(
                    if (active) com.youjixingtu.app.R.color.white else com.youjixingtu.app.R.color.muted,
                    null
                )
            )
        }
        refresh()
    }
}
