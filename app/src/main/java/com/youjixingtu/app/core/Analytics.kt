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

    /** 当前油箱剩余油量 */
    fun calculateRemainingFuel(records: List<FuelRecord>, tankCapacity: Int): Double? {
        if (records.isEmpty()) return null
        val sorted = records.sortedByDescending { parseDate(it.date) }
        var totalFuelAdded = 0.0
        var totalDistanceAfterRefuel = 0.0
        var foundFull = false

        for (i in sorted.indices) {
            val r = sorted[i]
            val liters = r.refuelData?.fuelLiters
            if (r.isRefuel && r.tankStatus == "full" && liters != null && liters > 0) {
                totalFuelAdded = liters
                foundFull = true
                break
            }
            if (r.isRefuel && liters != null) totalFuelAdded += liters

            if (i > 0) {
                val older = sorted[i - 1]
                if (!r.mileageForgotten && !older.mileageForgotten &&
                    r.mileage != null && older.mileage != null
                ) {
                    val distance = older.mileage!! - r.mileage!!
                    if (distance > 0) totalDistanceAfterRefuel += distance
                }
            }
        }
        if (!foundFull && totalFuelAdded == 0.0) return null

        val consumption = calculateFuelConsumption(records) ?: 8.0
        val fuelConsumed = totalDistanceAfterRefuel * consumption / 100.0
        return (totalFuelAdded - fuelConsumed).coerceIn(0.0, tankCapacity.toDouble())
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

    /** 生成唯一 ID，格式与旧版类似 */
    fun newId(): String =
        "${System.currentTimeMillis()}-${(Math.random() * 1e9).toLong().toString(36)}-${System.nanoTime().toString(36)}"
}
