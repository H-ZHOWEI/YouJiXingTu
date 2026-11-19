package com.youjixingtu.app.model

/**
 * 数据模型与 HBuilderX 旧版导出的 JSON 字段保持一致，
 * 这样旧版「导出星图」得到的文本可以在新版直接导入。
 */
data class ForgottenFields(
    var mileage: Boolean = false
)

data class ForgottenFuelFields(
    var gasStation: Boolean = false,
    var fuelLiters: Boolean = false,
    var fuelCost: Boolean = false,
    var fuelType: Boolean = false,
    var gaugeBefore: Boolean = false,
    var gaugeAfter: Boolean = false
)

data class RefuelData(
    var gasStation: String? = null,
    var fuelLiters: Double? = null,
    var fuelCost: Double? = null,
    var fuelType: String? = null,
    /** 加油前油表格数（选填，v1.14） */
    var gaugeBefore: Double? = null,
    /** 加油后油表格数（选填，v1.14） */
    var gaugeAfter: Double? = null,
    var forgottenFields: ForgottenFuelFields? = null
)

data class FuelRecord(
    var id: String = "",
    var date: String = "",
    var timestamp: Long = 0L,
    var mileage: Int? = null,
    var destination: String = "",
    var isRefuel: Boolean = false,
    var tankStatus: String = "full",
    /** 当前油表格数（选填，任何记录可填，v1.14） */
    var gaugeNow: Double? = null,
    var forgottenFields: ForgottenFields? = null,
    var refuelData: RefuelData? = null
) {
    val mileageForgotten: Boolean
        get() = forgottenFields?.mileage == true

    fun fuelValue(key: String): String? {
        val data = refuelData ?: return null
        val forgotten = data.forgottenFields
        return when (key) {
            "gasStation" -> if (forgotten?.gasStation == true) "[已忘记]" else data.gasStation
            "fuelLiters" -> if (forgotten?.fuelLiters == true) "[已忘记]" else data.fuelLiters?.toString()
            "fuelCost" -> if (forgotten?.fuelCost == true) "[已忘记]" else data.fuelCost?.toString()
            "fuelType" -> if (forgotten?.fuelType == true) "[已忘记]" else data.fuelType
            else -> null
        }
    }
}

data class CameraPhoto(
    var id: Long = 0L,
    var path: String = "",
    var date: String = ""
)
