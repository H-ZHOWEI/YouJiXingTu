package com.youjixingtu.app.util

/**
 * 与旧版 extractInfoFromText 完全相同的提取规则。
 */
object TextExtractHelper {

    data class Result(
        val gasStation: String?,
        val fuelLiters: Double?,
        val fuelCost: Double?,
        val fuelType: String?
    )

    fun extract(text: String): Result {
        val stationPatterns = listOf(
            Regex("(中国石化|中石化|中国石油|中石油|壳牌|BP|加油站)"),
            Regex("站名[:：]\\s*([^\\n]+)"),
            Regex("^([^\\n]+加油站)", RegexOption.MULTILINE)
        )
        var station: String? = null
        for (pattern in stationPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                station = (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value)
                    .replace(Regex("站名[:：]\\s*"), "").trim()
                break
            }
        }

        val literPatterns = listOf(
            Regex("加油量[:：]\\s*([0-9.]+)\\s*升"),
            Regex("([0-9.]+)\\s*升"),
            Regex("数量[:：]\\s*([0-9.]+)")
        )
        var liters: Double? = null
        for (pattern in literPatterns) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (value != null && value > 0 && value < 200) {
                liters = value
                break
            }
        }

        val costPatterns = listOf(
            Regex("金额[:：]\\s*([0-9.]+)\\s*元"),
            Regex("¥\\s*([0-9.]+)"),
            Regex("总计[:：]\\s*([0-9.]+)"),
            Regex("([0-9]{2,3}\\.[0-9]{2})\\s*元")
        )
        var cost: Double? = null
        for (pattern in costPatterns) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (value != null && value > 0 && value < 1000) {
                cost = value
                break
            }
        }

        // I7：油品词表扩展，兼容 89/92/95/98 汽油、0/-10 柴油、乙醇汽油及“油品/品名/型号”标签
        val typePatterns = listOf(
            Regex("(89号汽油|92号汽油|95号汽油|98号汽油|0号柴油|-10号柴油|10号柴油|乙醇汽油|汽油|柴油)", RegexOption.IGNORE_CASE),
            Regex("(?:油品|品名|型号|油品名称)[:：]?\\s*([^\\n]{1,12})")
        )
        var type: String? = null
        for (pattern in typePatterns) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
            type = when {
                raw.contains("89") -> "89"
                raw.contains("92") -> "92"
                raw.contains("95") -> "95"
                raw.contains("98") -> "98"
                raw.contains("乙醇") -> "92" // 乙醇汽油默认按 92 处理
                raw.contains("柴油") || raw.contains("-10") || raw.contains("0号") || raw.trim() == "0" -> "0"
                raw.contains("汽油") -> "92" // 只识别到“汽油”时给最常见默认值，用户可改
                else -> raw.trim()
            }
            break
        }

        return Result(station, liters, cost, type)
    }
}
