package com.youjixingtu.app.core

import com.youjixingtu.app.model.FuelRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 全部统计算法按 HBuilderX 旧版 script.js 原样移植，保证统计结果一致。
 */
object Analytics {

    private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm"

    fun parseDate(value: String): Date = try {
        SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).parse(value) ?: Date(0)
    } catch (e: Exception) {
        Date(0)
    }

    fun formatDateTime(date: Date): String =
        SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(date)

    fun formatDateOnly(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)

    fun formatTimeOnly(date: Date): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)

    private fun validMileageRecords(records: List<FuelRecord>) =
        records.filter { !it.mileageForgotten && it.mileage != null }

    fun maxMileageBeforeDate(records: List<FuelRecord>, date: Date, excludeId: String? = null): Int {
        var max = 0
        validMileageRecords(records).forEach { r ->
            if (r.id != excludeId && parseDate(r.date).before(date) && (r.mileage ?: 0) > max) {
                max = r.mileage!!
            }
        }
        return max
    }

    fun checkSameDayMileageOrder(
        records: List<FuelRecord>,
        date: Date,
        mileage: Int,
        excludeId: String? = null
    ): Boolean {
        val dayKey = formatDateOnly(date)
        val sameDay = validMileageRecords(records).filter { r ->
            r.id != excludeId && formatDateOnly(parseDate(r.date)) == dayKey
        }.toMutableList()
        if (sameDay.isEmpty()) return true

        sameDay.add(FuelRecord(id = "__temp__", date = formatDateTime(date), mileage = mileage))
        val sorted = sameDay.sortedBy { parseDate(it.date) }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1].mileage ?: return false
            val curr = sorted[i].mileage ?: return false
            if (curr <= prev) return false
        }
        return true
    }

    data class MileageValidation(val ok: Boolean, val message: String? = null)

    fun validateMileage(
        records: List<FuelRecord>,
        date: Date,
        mileage: Int,
        excludeId: String? = null
    ): MileageValidation {
        val sameDayOk = checkSameDayMileageOrder(records, date, mileage, excludeId)
        val previousMax = maxMileageBeforeDate(records, date, excludeId)
        return when {
            !sameDayOk -> MileageValidation(false, "同一天内，时间早的记录公里数必须小于时间晚的记录")
            mileage < previousMax -> MileageValidation(false, "公里数不能小于之前日期的记录（最大：$previousMax km）")
            else -> MileageValidation(true)
        }
    }

    /** 平均油耗：只采用「上一次加满 → 下一次加油」且间隔不超过 30 天的记录对 */
    fun calculateFuelConsumption(records: List<FuelRecord>): Double? {
        val valid = validMileageRecords(records)
        if (valid.size < 2) return null
        val refuels = valid.filter { it.isRefuel && it.refuelData != null }
            .sortedBy { it.mileage }
        if (refuels.size < 2) return null

        var totalFuel = 0.0
        var totalDistance = 0.0
        var pairs = 0
        for (i in 1 until refuels.size) {
            val prev = refuels[i - 1]
            val curr = refuels[i]
            if (prev.tankStatus != "full") continue
            val distance = (curr.mileage ?: 0) - (prev.mileage ?: 0)
            val fuel = prev.refuelData?.fuelLiters ?: 0.0
            val days = kotlin.math.abs(parseDate(curr.date).time - parseDate(prev.date).time) / 86_400_000.0
            if (days > 30) continue
            if (distance > 0 && fuel > 0) {
                totalFuel += fuel
                totalDistance += distance
                pairs++
            }
        }
        return if (totalDistance > 0 && pairs > 0) (totalFuel / totalDistance) * 100 else null
    }

    /** 根据加满油记录估算油箱容量，取最接近的常见容量 */
    fun estimateTankCapacity(records: List<FuelRecord>): Int {
        val fullRefuels = records.filter {
            it.isRefuel && it.tankStatus == "full" && (it.refuelData?.fuelLiters ?: 0.0) > 0
        }
        if (fullRefuels.isEmpty()) return 50

        val maxRefuel = fullRefuels.maxOf { it.refuelData!!.fuelLiters!! }
        val avgRefuel = fullRefuels.map { it.refuelData!!.fuelLiters!! }.average()
        var estimated = maxOf(maxRefuel, avgRefuel * 1.2)

        val common = listOf(30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 90, 100)
        var closest = common[0]
        for (c in common) {
            if (kotlin.math.abs(c - estimated) < kotlin.math.abs(closest - estimated)) closest = c
        }
        if (kotlin.math.abs(closest - estimated) <= 10) estimated = closest.toDouble()
        return kotlin.math.round(estimated).toInt().coerceIn(30, 150)
    }

    /**
     * 当前油箱剩余油量。
     * 以最近一次「加满」为起点，此刻油箱视为满箱（=tankCapacity，而不是那次加了多少升）；
     * 再按时间正序往后逐段推算：行驶扣油、加油补油，全程钳制在 [0, 油箱容量]。
     * 这样加满之后的中途加油不会被丢弃，剩余油量也不会轻易变成 0。
     */
    fun calculateRemainingFuel(records: List<FuelRecord>, tankCapacity: Int): Double? {
        if (records.isEmpty()) return null
        val chronological = records.sortedBy { parseDate(it.date) }

        // 最近一次加满（时间正序里最后一个满足条件的）
        val fullIndex = chronological.indexOfLast {
            it.isRefuel && it.tankStatus == "full" && (it.refuelData?.fuelLiters ?: 0.0) > 0
        }
        // 从未记录过加满：缺少可靠起点，不做臆测，交给上层显示 "--"
        if (fullIndex < 0) return null

        val consumption = calculateFuelConsumption(records) ?: 8.0
        var fuel = tankCapacity.toDouble() // 加满即满箱
        for (i in fullIndex + 1 until chronological.size) {
            val prev = chronological[i - 1]
            val cur = chronological[i]

            // 这一段行驶的油耗（相邻两条记录里程差，忽略「忘记里程」的记录）
            if (!prev.mileageForgotten && !cur.mileageForgotten &&
                prev.mileage != null && cur.mileage != null
            ) {
                val distance = cur.mileage!! - prev.mileage!!
                if (distance > 0) fuel -= distance * consumption / 100.0
            }

            // 这一条若是加油（含部分加油），把油量加回去
            if (cur.isRefuel) {
                val liters = cur.refuelData?.fuelLiters
                if (liters != null && liters > 0) fuel += liters
            }

            fuel = fuel.coerceIn(0.0, tankCapacity.toDouble())
        }
        return fuel.coerceIn(0.0, tankCapacity.toDouble())
    }

    data class DashboardStats(
        val avgConsumption: Double?,
        val monthCost: Double,
        val monthDistance: Int?
    )

    fun dashboardStats(records: List<FuelRecord>): DashboardStats {
        if (records.size < 2) return DashboardStats(null, 0.0, null)
        val now = Calendar.getInstance()
        val monthValid = validMileageRecords(records).filter {
            val d = parseDate(it.date)
            d.getCalendarField(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                d.getCalendarField(Calendar.YEAR) == now.get(Calendar.YEAR)
        }
        val monthDistance = if (monthValid.size >= 2) {
            val sorted = monthValid.sortedBy { it.mileage }
            (sorted.last().mileage ?: 0) - (sorted.first().mileage ?: 0)
        } else null

        val monthCost = records.filter {
            val d = parseDate(it.date)
            d.getCalendarField(Calendar.MONTH) == now.get(Calendar.MONTH) &&
                d.getCalendarField(Calendar.YEAR) == now.get(Calendar.YEAR)
        }.sumOf { it.refuelData?.fuelCost ?: 0.0 }

        return DashboardStats(calculateFuelConsumption(records), monthCost, monthDistance)
    }

    private fun Date.getCalendarField(field: Int): Int {
        val c = Calendar.getInstance()
        c.time = this
        return c.get(field)
    }

    data class PeriodStats(
        val hasData: Boolean,
        val distance: Int = 0,
        val cost: Double = 0.0,
        val fuel: Double = 0.0,
        val consumption: Double = 0.0,
        val costPerKm: Double = 0.0
    )

    fun periodStats(records: List<FuelRecord>, period: String): PeriodStats {
        if (records.isEmpty()) return PeriodStats(false)

        val start: Date
        val end: Date
        when (period) {
            "week" -> {
                val s = Calendar.getInstance()
                s.set(Calendar.HOUR_OF_DAY, 0)
                s.set(Calendar.MINUTE, 0)
                s.set(Calendar.SECOND, 0)
                s.set(Calendar.MILLISECOND, 0)
                s.add(Calendar.DAY_OF_MONTH, -(s.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
                start = s.time
                val e = Calendar.getInstance()
                e.time = s.time
                e.add(Calendar.DAY_OF_MONTH, 6)
                e.set(Calendar.HOUR_OF_DAY, 23)
                e.set(Calendar.MINUTE, 59)
                e.set(Calendar.SECOND, 59)
                e.set(Calendar.MILLISECOND, 999)
                end = e.time
            }
            "month" -> {
                start = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
                end = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.time
            }
            "year" -> {
                start = Calendar.getInstance().apply {
                    set(Calendar.MONTH, Calendar.JANUARY)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.time
                end = Calendar.getInstance().apply {
                    set(Calendar.MONTH, Calendar.DECEMBER)
                    set(Calendar.DAY_OF_MONTH, 31)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.time
            }
            else -> {
                start = Date(0)
                end = Date()
            }
        }
        val inRange = { r: FuelRecord ->
            val d = parseDate(r.date)
            !d.before(start) && !d.after(end)
        }
        val validPeriod = validMileageRecords(records).filter(inRange)
        if (validPeriod.size < 2) return PeriodStats(false)

        val sorted = validPeriod.sortedBy { it.mileage }
        val distance = (sorted.last().mileage ?: 0) - (sorted.first().mileage ?: 0)
        val allPeriod = records.filter(inRange)
        val cost = allPeriod.sumOf { it.refuelData?.fuelCost ?: 0.0 }
        val fuel = allPeriod.sumOf { it.refuelData?.fuelLiters ?: 0.0 }
        val consumption = if (distance > 0) fuel / distance * 100 else 0.0
        val costPerKm = if (distance > 0) cost / distance else 0.0
        return PeriodStats(true, distance, cost, fuel, consumption, costPerKm)
    }

    data class RefuelHabits(val avgAmount: Double, val avgIntervalDays: Double, val total: Int)

    fun refuelHabits(records: List<FuelRecord>): RefuelHabits {
        val refuels = records.filter { it.isRefuel && (it.refuelData?.fuelLiters ?: 0.0) > 0 }
        if (refuels.isEmpty()) return RefuelHabits(0.0, 0.0, 0)
        val avgAmount = refuels.map { it.refuelData!!.fuelLiters!! }.average()
        val sorted = refuels.sortedBy { parseDate(it.date) }
        var totalInterval = 0.0
        var count = 0
        for (i in 1 until sorted.size) {
            totalInterval += (parseDate(sorted[i].date).time - parseDate(sorted[i - 1].date).time) / 86_400_000.0
            count++
        }
        return RefuelHabits(avgAmount, if (count > 0) totalInterval / count else 0.0, refuels.size)
    }

    data class Gap(
        val startDate: String,
        val endDate: String,
        val days: Int,
        val startMileage: Int,
        val endMileage: Int,
        val hasRefuelData: Boolean
    )

    fun detectDataGaps(records: List<FuelRecord>): List<Gap> {
        val sorted = validMileageRecords(records).sortedBy { parseDate(it.date) }
        if (sorted.size < 2) return emptyList()
        val gaps = mutableListOf<Gap>()
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val days = (parseDate(curr.date).time - parseDate(prev.date).time) / 86_400_000.0
            if (days > 30) {
                gaps.add(
                    Gap(
                        prev.date, curr.date, Math.round(days).toInt(),
                        prev.mileage ?: 0, curr.mileage ?: 0,
                        hasRefuelBetween(records, prev.date, curr.date)
                    )
                )
            }
        }
        return gaps
    }

    private fun hasRefuelBetween(records: List<FuelRecord>, start: String, end: String): Boolean {
        val s = parseDate(start)
        val e = parseDate(end)
        return records.any { r ->
            val d = parseDate(r.date)
            d.after(s) && d.before(e) && r.isRefuel && r.refuelData != null &&
                ((r.refuelData?.fuelCost ?: 0.0) > 0 || (r.refuelData?.fuelLiters ?: 0.0) > 0)
        }
    }

    data class GapStats(
        val gapCount: Int,
        val recordCount: Int,
        val totalCost: Double,
        val totalLiters: Double,
        val avgCostPerLiter: Double,
        val fuelTypes: Set<String>,
        val stations: Set<String>,
        val gaps: List<Gap>
    )

    fun gapStats(records: List<FuelRecord>): GapStats? {
        val gaps = detectDataGaps(records)
        if (gaps.isEmpty()) return null
        var cost = 0.0
        var liters = 0.0
        var count = 0
        val types = mutableSetOf<String>()
        val stations = mutableSetOf<String>()
        gaps.forEach { gap ->
            val s = parseDate(gap.startDate)
            val e = parseDate(gap.endDate)
            val inGap = records.filter { r ->
                val d = parseDate(r.date)
                d.after(s) && d.before(e) && r.isRefuel && r.refuelData != null
            }
            count += inGap.size
            inGap.forEach { r ->
                cost += r.refuelData?.fuelCost ?: 0.0
                liters += r.refuelData?.fuelLiters ?: 0.0
                r.refuelData?.fuelType?.let { types.add(it) }
                r.refuelData?.gasStation?.let { stations.add(it) }
            }
        }
        return GapStats(
            gaps.size, count, cost, liters,
            if (liters > 0) cost / liters else 0.0,
            types, stations, gaps
        )
    }

    fun assessDataQuality(records: List<FuelRecord>): String {
        if (validMileageRecords(records).size < 2) return "good"
        val stats = gapStats(records) ?: return "good"
        val totalRefuels = records.count { it.isRefuel }
        if (totalRefuels == 0) return "good"
        val ratio = stats.recordCount.toDouble() / totalRefuels
        return when {
            ratio > 0.3 || stats.gapCount > 3 -> "poor"
            ratio > 0.1 || stats.gapCount > 1 -> "fair"
            else -> "good"
        }
    }

    data class StationStat(
        val name: String,
        val count: Int,
        val avgConsumption: Double,
        val avgPricePerLiter: Double,
        val avgFuelLiters: Double,
        val totalCost: Double,
        val fuelTypes: String,
        val rating: Double
    )

    fun stationComparisons(records: List<FuelRecord>): List<StationStat> {
        val valid = records.filter { r ->
            val f = r.refuelData
            r.isRefuel && r.tankStatus == "full" && f != null &&
                !f.gasStation.isNullOrBlank() && (f.fuelLiters ?: 0.0) > 0 &&
                (f.fuelCost ?: 0.0) > 0 && !f.fuelType.isNullOrBlank() &&
                f.forgottenFields?.gasStation != true &&
                f.forgottenFields?.fuelLiters != true &&
                f.forgottenFields?.fuelCost != true &&
                f.forgottenFields?.fuelType != true
        }
        if (valid.isEmpty()) return emptyList()

        return valid.groupBy { it.refuelData!!.gasStation!! }
            .map { (name, group) ->
                val sorted = group.sortedBy { parseDate(it.date) }
                val litersList = sorted.map { it.refuelData!!.fuelLiters!! }
                val avgLiters = litersList.average()
                val totalLiters = litersList.sum()
                val totalCost = sorted.sumOf { it.refuelData!!.fuelCost!! }
                val avgPrice = if (totalLiters > 0) totalCost / totalLiters else 0.0

                var fuelUsed = 0.0
                var distance = 0.0
                for (i in 1 until sorted.size) {
                    val prev = sorted[i - 1]
                    val curr = sorted[i]
                    val d = (curr.mileage ?: 0) - (prev.mileage ?: 0)
                    val f = prev.refuelData!!.fuelLiters!!
                    if (d > 0 && f > 0) {
                        fuelUsed += f
                        distance += d
                    }
                }
                val avgConsumption = if (distance > 0) fuelUsed / distance * 100 else 0.0
                val rating = if (avgConsumption > 0) {
                    val consumptionScore = (10 - (avgConsumption - 8) * 0.5).coerceAtLeast(0.0)
                    val priceScore = (10 - (avgPrice - 7) * 2).coerceAtLeast(0.0)
                    (consumptionScore + priceScore) / 2
                } else 5.0

                StationStat(
                    name = name,
                    count = sorted.size,
                    avgConsumption = avgConsumption,
                    avgPricePerLiter = avgPrice,
                    avgFuelLiters = avgLiters,
                    totalCost = totalCost,
                    fuelTypes = sorted.map { it.refuelData!!.fuelType!! }.distinct().joinToString("、"),
                    rating = rating
                )
            }
            .sortedByDescending { it.rating }
    }

    /** 清理规则：仅保留本月与上月全部记录；更早月份保留首条、末条和所有加油记录 */
    fun redundantRecordIds(records: List<FuelRecord>): Set<String> {
        if (records.isEmpty()) return emptySet()
        val now = Calendar.getInstance()
        val keepMonths = mutableSetOf<String>()
        keepMonths.add("${now.get(Calendar.YEAR)}-${now.get(Calendar.MONTH)}")
        val prev = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        keepMonths.add("${prev.get(Calendar.YEAR)}-${prev.get(Calendar.MONTH)}")

        val groups = records.groupBy { r ->
            val c = Calendar.getInstance()
            c.time = parseDate(r.date)
            "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH)}"
        }
        val redundant = mutableSetOf<String>()
        groups.forEach { (month, list) ->
            if (keepMonths.contains(month)) return@forEach
            val sorted = list.sortedBy { parseDate(it.date) }
            val keep = mutableSetOf<String>()
            keep.add(sorted.first().id)
            keep.add(sorted.last().id)
            sorted.filter { it.isRefuel }.forEach { keep.add(it.id) }
            sorted.forEach { if (!keep.contains(it.id)) redundant.add(it.id) }
        }
        return redundant
    }

    /** 导入旧版数据时补齐缺省字段 */
    fun normalizeImported(records: List<FuelRecord>): List<FuelRecord> {
        records.forEach { r ->
            if (r.timestamp == 0L) r.timestamp = parseDate(r.date).time
            if (r.tankStatus.isBlank()) r.tankStatus = "full"
            if (r.forgottenFields == null) r.forgottenFields = com.youjixingtu.app.model.ForgottenFields()
            if (r.refuelData != null && r.refuelData!!.forgottenFields == null) {
                r.refuelData!!.forgottenFields = com.youjixingtu.app.model.ForgottenFuelFields()
            }
        }
        return records
    }

    // ==================== v1.12 智能化推断 ====================

    /** I1：按加油量占油箱容量的比例推断油箱状态；无法可靠判断时返回 null（保持手动） */
    fun inferTankStatus(liters: Double?, tankCapacity: Int): String? {
        if (liters == null || liters <= 0 || tankCapacity <= 0) return null
        val ratio = liters / tankCapacity
        return when {
            ratio >= 0.9 -> "full"
            ratio >= 0.35 -> "partial"
            else -> null
        }
    }

    /** I2：某加油站历史最常用的油品（忽略“已忘记”） */
    fun stationFuelType(records: List<FuelRecord>, station: String): String? {
        if (station.isBlank()) return null
        return records.mapNotNull { r ->
            val f = r.refuelData
            if (r.isRefuel && f?.gasStation?.trim() == station.trim() && !f.fuelType.isNullOrBlank()
                && f.forgottenFields?.fuelType != true
            ) f.fuelType else null
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /** I5/I6：历史平均单价（总金额/总升数，按量加权）；station 为空表示全部加油站 */
    fun historicalPricePerLiter(records: List<FuelRecord>, station: String? = null): Double? {
        val pool = records.filter { r ->
            val f = r.refuelData ?: return@filter false
            r.isRefuel && (f.fuelCost ?: 0.0) > 0 && (f.fuelLiters ?: 0.0) > 0 &&
                f.forgottenFields?.fuelCost != true && f.forgottenFields?.fuelLiters != true &&
                (station.isNullOrBlank() || f.gasStation?.trim() == station.trim())
        }
        val liters = pool.sumOf { it.refuelData!!.fuelLiters!! }
        val cost = pool.sumOf { it.refuelData!!.fuelCost!! }
        return if (liters > 0 && cost > 0) cost / liters else null
    }

    /** I3：重复记录检测——同一天已加油，或里程与某条加油记录几乎相同 */
    fun detectDuplicateHint(
        records: List<FuelRecord>, date: Date, mileage: Int?, isRefuel: Boolean, excludeId: String? = null
    ): String? {
        if (!isRefuel) return null
        val dayKey = formatDateOnly(date)
        val sameDay = records.any { r ->
            r.id != excludeId && r.isRefuel && formatDateOnly(parseDate(r.date)) == dayKey
        }
        if (sameDay) return "同一天已经有一条加油记录了，确认不是重复记录？"
        if (mileage != null) {
            val near = validMileageRecords(records).any { r ->
                r.id != excludeId && r.isRefuel && r.mileage != null && kotlin.math.abs(r.mileage!! - mileage) <= 2
            }
            if (near) return "有一条加油记录的里程与本次几乎相同（$mileage km），确认不是重复记录？"
        }
        return null
    }

    /** I4：里程量级异常提醒——相对历史最大里程跳变过大（疑似多打一位） */
    fun mileageAnomalyHint(records: List<FuelRecord>, date: Date, mileage: Int, excludeId: String? = null): String? {
        val prevMax = maxMileageBeforeDate(records, date, excludeId)
        if (prevMax <= 0) return null
        val sorted = validMileageRecords(records).filter { it.id != excludeId }.sortedBy { parseDate(it.date) }
        val increments = mutableListOf<Int>()
        for (i in 1 until sorted.size) {
            val d = (sorted[i].mileage ?: 0) - (sorted[i - 1].mileage ?: 0)
            if (d > 0) increments.add(d)
        }
        val typical = if (increments.isNotEmpty()) increments.sorted()[increments.size / 2] else 0
        val jump = mileage - prevMax
        if (jump > maxOf(500, typical * 5)) {
            return "比上次（$prevMax km）多了 $jump km，平时一次约 $typical km，是不是多打了一位？"
        }
        return null
    }

    data class RefuelPrediction(val message: String, val days: Int?, val km: Int?)

    /** I9：下次加油预测——剩余油量按油耗折算可行驶公里，再按个人加油习惯折算天数 */
    fun predictNextRefuel(records: List<FuelRecord>, tankCapacity: Int): RefuelPrediction? {
        val remaining = calculateRemainingFuel(records, tankCapacity) ?: return null
        val consumption = calculateFuelConsumption(records) ?: return null
        val km = (remaining / consumption * 100).toInt()
        val habits = refuelHabits(records)
        val days = if (habits.avgAmount > 0 && habits.avgIntervalDays > 0)
            (remaining / habits.avgAmount * habits.avgIntervalDays).toInt() else null
        val msg = buildString {
            append("按当前油量约还能行驶 $km 公里")
            if (days != null) append("，约 $days 天后需要加油")
        }
        return RefuelPrediction(msg, days, km)
    }

    /** I10：手动容量与估算容量偏差过大时提示 */
    fun capacityConflictHint(manual: Int, estimated: Int): String? {
        if (estimated <= 0) return null
        val diff = kotlin.math.abs(manual - estimated).toDouble() / estimated
        return if (diff > 0.3) "你填的 $manual 升与按记录估算的 $estimated 升相差较大，确认无误？" else null
    }

    /** I11：单段油耗明显偏离整体平均时提示可能记错 */
    fun anomalousConsumptionHint(records: List<FuelRecord>): String? {
        val avg = calculateFuelConsumption(records) ?: return null
        val valid = validMileageRecords(records).filter {
            it.isRefuel && it.tankStatus == "full" && (it.refuelData?.fuelLiters ?: 0.0) > 0
        }.sortedBy { parseDate(it.date) }
        if (valid.size < 2) return null
        for (i in 1 until valid.size) {
            val prev = valid[i - 1]
            val curr = valid[i]
            val d = (curr.mileage ?: 0) - (prev.mileage ?: 0)
            val f = prev.refuelData?.fuelLiters ?: 0.0
            if (d <= 0 || f <= 0) continue
            val seg = f / d * 100
            if (seg > avg * 1.8 || seg < avg * 0.4) {
                val segTxt = String.format(Locale.getDefault(), "%.1f", seg)
                val avgTxt = String.format(Locale.getDefault(), "%.1f", avg)
                return "${formatDateOnly(parseDate(prev.date))} 前后一段油耗约 $segTxt L/100km，明显偏离平均 $avgTxt，可能里程或油量记错了"
            }
        }
        return null
    }

    /** I12：数据质量的可操作建议 */
    fun qualityAdvice(records: List<FuelRecord>): String? {
        val quality = assessDataQuality(records)
        if (quality == "good") return null
        val gaps = gapStats(records)
        return when {
            quality == "poor" -> "数据里有较多超过 30 天的空白时段，建议到「星迹」补记缺失的加油，或用「清理冗余」整理。"
            gaps != null -> "检测到 ${gaps.gapCount} 段空白期，可到「星迹」补记或清理冗余记录，统计会更准。"
            else -> null
        }
    }

    /** I15：常用目的地（按使用频次排序），用于输入补全 */
    fun frequentDestinations(records: List<FuelRecord>, limit: Int = 10): List<String> =
        records.mapNotNull { it.destination.trim().takeIf { v -> v.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit).map { it.key }

    /** 生成唯一 ID，格式与旧版类似 */
    fun newId(): String =
        "${System.currentTimeMillis()}-${(Math.random() * 1e9).toLong().toString(36)}-${System.nanoTime().toString(36)}"
}
