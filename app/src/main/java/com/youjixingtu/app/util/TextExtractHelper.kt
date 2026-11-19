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

        val typePatterns = listOf(
            Regex("(92号汽油|95号汽油|98号汽油|0号柴油)"),
            Regex("油品[:：]\\s*([^\\n]+)")
        )
        var type: String? = null
        for (pattern in typePatterns) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
            type = when {
                raw.contains("92") -> "92"
                raw.contains("95") -> "95"
                raw.contains("98") -> "98"
                raw.contains("0") || raw.contains("柴油") -> "0"
                else -> raw.trim()
            }
            break
        }

        return Result(station, liters, cost, type)
    }

    const val MOCK_RECEIPT = """中国石化加油站
收据号: 20231215001
日期: 2023-12-15 14:30

92号汽油
加油量: 45.6升
单价: 7.85元/升
金额: 357.96元

支付方式: 微信支付
感谢您的光临！"""
}
