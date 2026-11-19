package com.youjixingtu.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.youjixingtu.app.R
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.FragmentRecordBinding
import com.youjixingtu.app.model.ForgottenFields
import com.youjixingtu.app.model.ForgottenFuelFields
import com.youjixingtu.app.model.FuelRecord
import com.youjixingtu.app.model.RefuelData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null
    private val binding get() = _binding!!

    private var records = mutableListOf<FuelRecord>()
    private var dateTimeLocked = false
    private var currentDate = Date()
    /** 程序化设置 refuelCheck 时抑制弹页 */
    private var suppressStationPrompt = false
    /** 「我忘记了」勾选前的原值，取消勾选时还原（C4） */
    private val forgotSavedValues = mutableMapOf<Int, String>()
    /** I13：空数据引导只提示一次 */
    private var hasShownEmptyGuide = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())

    private lateinit var nearbyStationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearbyStationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val station = result.data?.getStringExtra(NearbyStationActivity.EXTRA_STATION)?.trim().orEmpty()
                if (station.isNotBlank()) {
                    applyPickedStation(station)
                }
            }
        }
    }

    /** I8：供外部（低油量横幅）一键直达选加油站页 */
    fun openNearbyStation() {
        switchToRecordTab()
        suppressStationPrompt = false
        binding.refuelCheck.isChecked = true
        val intent = Intent(requireContext(), NearbyStationActivity::class.java)
        nearbyStationLauncher.launch(intent)
    }

    private fun switchToRecordTab() {
        (activity as? MainActivity)?.selectTab(R.id.nav_record)
    }

    /** 将选中的加油站填入「加油站名称」与「去了哪里」 */
    private fun applyPickedStation(station: String) {
        binding.gasStationInput.setText(station)
        // A5：选站后若目的地已填则不覆盖；选站是用户主动行为，这里仍以站名填充空目的地
        if (binding.destinationInput.text.isNullOrBlank()) binding.destinationInput.setText(station)
        Repository.lastGasStation = station
        requireContext().toast("已填入：$station，可修改", long = true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
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
        records = Repository.loadRecords().toMutableList()
        records.sortByDescending { Analytics.parseDate(it.date) }

        if (!dateTimeLocked) {
            currentDate = Date()
            binding.dateBtn.text = dateFormat.format(currentDate)
        }
        binding.mileageInput.setText(getLastMileage().toString().takeIf { it != "0" } ?: "")
        binding.fuelTypeSpinner.setSelection(fuelTypePosition(requireContext(), Repository.lastFuelType))

        setupDestinationSuggestions()
        updateEstimateCard()
        updateMiniStats()
        updateSmartHint()
        // I13：首次进入且无任何记录时给一次上手引导
        if (records.isEmpty() && !hasShownEmptyGuide) {
            hasShownEmptyGuide = true
            requireContext().toast("欢迎使用！手动记一笔，记满 2 次加油就能自动算油耗", long = true)
        }
    }

    /** 汇总当前表单可给出的智能提示（油量/容量冲突等） */
    private fun updateSmartHint() {
        if (records.isEmpty()) {
            smartHint(null)
            return
        }
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 }
            ?: Analytics.estimateTankCapacity(records)
        val manual = binding.manualTankCapacityInput.intOrNull()
        if (manual != null) {
            Analytics.capacityConflictHint(manual, capacity)?.let { smartHint(it); return }
        }
        val gauge = Analytics.gaugeEstimate(records, Repository.gaugeTotal, Repository.gaugeCurrent, Repository.gaugeCurrentDate)
        val pct = if (gauge.usable) gauge.percent
            else Analytics.calculateRemainingFuel(records, capacity)?.let { (it / capacity * 100).toInt() }
        if (pct != null && pct <= 15) {
            smartHint("⚠ 当前油量仅剩约 $pct%，建议尽快加油；勾选「本次加油了」可一键查找附近加油站。")
            return
        }
        smartHint(null)
    }

    private fun getLastMileage(): Int {
        val latest = records.firstOrNull { !it.mileageForgotten && it.mileage != null }
        return latest?.mileage ?: 0
    }

    /** 智能提示：在表单内就地展示，不打断输入 */
    private fun smartHint(message: String?) {
        binding.smartHintText.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.smartHintText.text = message.orEmpty()
    }

    /** I15：为「去了哪里」接入常用目的地补全 */
    private fun setupDestinationSuggestions() {
        val dest = binding.destinationInput as AutoCompleteTextView
        val suggestions = Analytics.frequentDestinations(records)
        dest.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, suggestions))
    }

    /** I2：选中加油站后按历史带出该站最常用油品；I10 油箱容量冲突；I1 加油量推断加满 */
    private fun applySmartFuelHints(station: String) {
        if (station.isBlank()) return
        Analytics.stationFuelType(records, station)?.let { type ->
            val pos = fuelTypePosition(requireContext(), type)
            if (pos > 0 && binding.fuelTypeSpinner.selectedItemPosition == 0) {
                binding.fuelTypeSpinner.setSelection(pos)
            }
        }
    }

    private fun inferTankFromLiters() {
        val liters = binding.fuelLitersInput.doubleOrNull()
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 }
            ?: Analytics.estimateTankCapacity(records)
        when (Analytics.inferTankStatus(liters, capacity)) {
            "full" -> binding.tankFullRadio.isChecked = true
            "partial" -> binding.tankPartialRadio.isChecked = true
            else -> {}
        }
    }

    private fun setupListeners() {
        binding.dateBtn.setOnClickListener { showDateTimePicker() }
        binding.lockDateTimeCheck.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            dateTimeLocked = checked
        }

        setupForgot(binding.forgotMileageCheck, binding.mileageInput, binding.mileageError)
        setupForgot(binding.forgotGasStationCheck, binding.gasStationInput, binding.gasStationError)
        setupForgot(binding.forgotFuelLitersCheck, binding.fuelLitersInput, binding.fuelLitersError)
        setupForgot(binding.forgotFuelCostCheck, binding.fuelCostInput, binding.fuelCostError)
        setupForgot(binding.forgotFuelTypeCheck, binding.fuelTypeSpinner, binding.fuelTypeError)

        binding.refuelCheck.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            binding.fuelDetailsLayout.show(checked)
            // A1/A3：仅「用户主动勾选 + 加油站仍为空」时弹附近站，避免 OCR 填充/重复勾选反复弹页
            if (checked && !suppressStationPrompt && binding.gasStationInput.text.isNullOrBlank()) {
                val intent = Intent(requireContext(), NearbyStationActivity::class.java)
                nearbyStationLauncher.launch(intent)
            }
            updateEstimateCard()
        }

        binding.gasStationInput.setOnEditorActionListener { v, _, _ ->
            // A5：仅当「去了哪里」为空时才带入站名，不覆盖用户已填的目的地
            if (binding.refuelCheck.isChecked && binding.destinationInput.text.isNullOrBlank()) {
                binding.destinationInput.setText(v.text.toString())
            }
            false
        }
        binding.gasStationInput.addTextChangedListener(simpleTextWatcher { text ->
            if (binding.refuelCheck.isChecked && binding.destinationInput.text.isNullOrBlank()) {
                binding.destinationInput.setText(text)
            }
            // I2：历史该站常用油品自动带出
            applySmartFuelHints(text.toString().trim())
        })

        // A4：移除「金额>200 且有小数即判加满」的粗暴推断，油箱状态完全交由用户手动选择

        binding.fuelTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = fuelTypeValue(requireContext(), position)
                if (value.isNotBlank()) Repository.lastFuelType = value
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.tankFullRadio.setOnCheckedChangeListener { _, _ -> updateEstimateCard() }
        binding.tankPartialRadio.setOnCheckedChangeListener { _, _ -> updateEstimateCard() }
        binding.fuelLitersInput.addTextChangedListener(simpleTextWatcher {
            inferTankFromLiters() // I1：按加油量推断是否加满
            updateEstimateCard()
        })
        // v1.14：填了加油后格数，实时预览格数法估算
        listOf(binding.gaugeBeforeInput, binding.gaugeAfterInput, binding.gaugeNowInput).forEach {
            it.addTextChangedListener(simpleTextWatcher { updateEstimateCard() })
        }
        binding.mileageInput.addTextChangedListener(simpleTextWatcher {
            if (!dateTimeLocked && it.isNotBlank()) {
                currentDate = Date()
                binding.dateBtn.text = dateFormat.format(currentDate)
            }
            updateEstimateCard()
        })

        binding.saveBtn.setOnClickListener { saveRecord() }
        binding.saveTankCapacityBtn.setOnClickListener {
            val capacity = binding.manualTankCapacityInput.intOrNull()
            if (capacity == null || capacity !in 20..200) {
                requireContext().toast("请输入有效的油箱容量（20-200升）")
                return@setOnClickListener
            }
            val estimated = Analytics.estimateTankCapacity(records)
            Analytics.capacityConflictHint(capacity, estimated)?.let { hint ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("请确认油箱容量")
                    .setMessage(hint)
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("确认保存") { _, _ ->
                        Repository.tankCapacity = capacity
                        requireContext().toast("油箱容量已设置为 $capacity 升")
                        updateEstimateCard()
                        updateSmartHint()
                    }
                    .show()
                return@setOnClickListener
            }
            Repository.tankCapacity = capacity
            requireContext().toast("油箱容量已设置为 $capacity 升")
            updateEstimateCard()
            updateSmartHint()
        }

        binding.saveGaugeCurrentBtn.setOnClickListener {
            val bars = binding.gaugeCurrentInput.doubleOrNull()
            if (bars == null || bars < 0 || bars > Repository.gaugeTotal) {
                requireContext().toast("请输入 0 ~ ${Repository.gaugeTotal} 之间的格数")
                return@setOnClickListener
            }
            Repository.gaugeCurrent = bars
            requireContext().toast("当前格数已更新为 $bars 格")
            updateEstimateCard()
        }
        binding.saveGaugeTotalBtn.setOnClickListener {
            val total = binding.gaugeTotalInput.intOrNull()
            if (total == null || total !in 2..30) {
                requireContext().toast("请输入 2 ~ 30 之间的总格数")
                return@setOnClickListener
            }
            Repository.gaugeTotal = total
            // 旧当前格数若超出新范围则清空
            Repository.gaugeCurrent?.let { if (it > total) Repository.gaugeCurrent = null }
            requireContext().toast("油表总格数已设置为 $total 格")
            updateEstimateCard()
        }
    }

    private fun setupForgot(check: CompoundButton, input: View, error: TextView) {
        check.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            input.isEnabled = !checked
            error.visibility = View.GONE
            if (input is EditText) {
                if (checked) {
                    // C4：勾选前暂存原值，取消勾选时还原
                    forgotSavedValues[input.id] = input.text?.toString().orEmpty()
                    input.text?.clear()
                } else {
                    val saved = forgotSavedValues.remove(input.id)
                    if (saved != null) input.setText(saved)
                }
            } else if (input is android.widget.Spinner) {
                input.isEnabled = !checked
            }
        }
    }

    private fun showDateTimePicker() {
        val cal = Calendar.getInstance()
        cal.time = currentDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        currentDate = cal.time
                        binding.dateBtn.text = dateFormat.format(currentDate)
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun saveRecord() {
        val forgotMileage = binding.forgotMileageCheck.isChecked
        val mileage = if (forgotMileage) null else binding.mileageInput.intOrNull()
        if (!forgotMileage && mileage == null) {
            requireContext().toast("请填写当前油表公里数或选择\"我忘记了\"")
            return
        }

        if (mileage != null) {
            val validation = Analytics.validateMileage(records, currentDate, mileage)
            if (!validation.ok) {
                binding.mileageError.text = validation.message
                binding.mileageError.visibility = View.VISIBLE
                return
            }
        }
        binding.mileageError.visibility = View.GONE

        val destination = binding.destinationInput.text.toString().trim()
        if (destination.isBlank()) {
            binding.destinationError.visibility = View.VISIBLE
            return
        }
        binding.destinationError.visibility = View.GONE

        val refuel = binding.refuelCheck.isChecked
        val station = binding.gasStationInput.text.toString().trim()
        val liters = binding.fuelLitersInput.doubleOrNull()
        val cost = binding.fuelCostInput.doubleOrNull()
        val gaugeBefore = binding.gaugeBeforeInput.doubleOrNull()
        val gaugeAfter = binding.gaugeAfterInput.doubleOrNull()
        val gaugeNow = binding.gaugeNowInput.doubleOrNull()
        val total = Repository.gaugeTotal
        val badGauge = listOf(gaugeBefore, gaugeAfter, gaugeNow).any { it != null && (it < 0 || it > total) }
        if (badGauge) {
            requireContext().toast("格数需在 0 ~ $total 之间")
            return
        }

        // I3：重复记录检测 —— 用户确认后才继续
        Analytics.detectDuplicateHint(records, currentDate, mileage, refuel)?.let { hint ->
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("可能是重复记录")
                .setMessage("$hint\n\n如果确实要记录，请点「仍然保存」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然保存") { _, _ -> persistRecord(mileage, destination, refuel, station, liters, cost, gaugeBefore, gaugeAfter, gaugeNow) }
                .show()
            return
        }
        // I4：里程量级异常提醒 —— 用户确认后才继续
        if (mileage != null) {
            Analytics.mileageAnomalyHint(records, currentDate, mileage)?.let { hint ->
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("请确认公里数")
                    .setMessage("$hint\n\n确认无误请点「继续保存」。")
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("继续保存") { _, _ -> persistRecord(mileage, destination, refuel, station, liters, cost, gaugeBefore, gaugeAfter, gaugeNow) }
                    .show()
                return
            }
        }
        persistRecord(mileage, destination, refuel, station, liters, cost, gaugeBefore, gaugeAfter, gaugeNow)
    }

    /** 真正落盘（从 saveRecord 抽出，供智能提示确认后复用） */
    private fun persistRecord(
        mileage: Int?, destination: String, refuel: Boolean, station: String,
        liters: Double?, cost: Double?, gaugeBefore: Double?, gaugeAfter: Double?, gaugeNow: Double?
    ) {
        val forgotMileage = binding.forgotMileageCheck.isChecked
        val fuelType = fuelTypeValue(requireContext(), binding.fuelTypeSpinner.selectedItemPosition)
        val forgotStation = binding.forgotGasStationCheck.isChecked
        val forgotLiters = binding.forgotFuelLitersCheck.isChecked
        val forgotCost = binding.forgotFuelCostCheck.isChecked
        val forgotType = binding.forgotFuelTypeCheck.isChecked

        if (refuel) {
            if (!forgotStation && station.isBlank()) {
                binding.gasStationError.visibility = View.VISIBLE
                return
            }
            if (!forgotLiters && binding.fuelLitersInput.text.isNullOrBlank()) {
                binding.fuelLitersError.visibility = View.VISIBLE
                return
            }
            if (!forgotCost && binding.fuelCostInput.text.isNullOrBlank()) {
                binding.fuelCostError.visibility = View.VISIBLE
                return
            }
            if (!forgotType && fuelType.isBlank()) {
                binding.fuelTypeError.visibility = View.VISIBLE
                return
            }
        }
        listOf(binding.gasStationError, binding.fuelLitersError, binding.fuelCostError, binding.fuelTypeError)
            .forEach { it.visibility = View.GONE }

        val tankStatus = if (binding.tankFullRadio.isChecked) "full" else "partial"
        val hasFuelData = refuel || station.isNotBlank() || liters != null || cost != null ||
            fuelType.isNotBlank() || gaugeBefore != null || gaugeAfter != null
        val record = FuelRecord(
            id = Analytics.newId(),
            date = dateFormat.format(currentDate),
            timestamp = System.currentTimeMillis(),
            mileage = mileage,
            destination = destination,
            isRefuel = refuel,
            tankStatus = tankStatus,
            gaugeNow = gaugeNow,
            forgottenFields = ForgottenFields(mileage = forgotMileage),
            refuelData = if (hasFuelData) RefuelData(
                gasStation = if (forgotStation) null else station.ifBlank { null },
                fuelLiters = if (forgotLiters) null else liters,
                fuelCost = if (forgotCost) null else cost,
                fuelType = if (forgotType) null else fuelType.ifBlank { null },
                gaugeBefore = gaugeBefore,
                gaugeAfter = gaugeAfter,
                forgottenFields = ForgottenFuelFields(forgotStation, forgotLiters, forgotCost, forgotType)
            ) else null
        )

        records.add(record)
        if (!Repository.saveRecords(records)) {
            records.remove(record)
            requireContext().toast("保存失败，请检查存储空间或清理冗余记录")
            return
        }
        // 记住本次加油站，供下次勾选「本次加油了」时自动填充
        if (!forgotStation && station.isNotBlank()) Repository.lastGasStation = station
        refresh()
        resetForm()
        requireContext().toast("星迹记录成功！当前共有 ${records.size} 条记录")
    }

    private fun resetForm() {
        binding.forgotMileageCheck.isChecked = false
        binding.mileageInput.isEnabled = true
        binding.mileageInput.setText(getLastMileage().toString().takeIf { it != "0" } ?: "")
        binding.destinationInput.text?.clear()
        binding.refuelCheck.isChecked = false
        binding.fuelDetailsLayout.show(false)
        binding.gasStationInput.text?.clear()
        binding.fuelLitersInput.text?.clear()
        binding.fuelCostInput.text?.clear()
        binding.gaugeBeforeInput.text?.clear()
        binding.gaugeAfterInput.text?.clear()
        binding.gaugeNowInput.text?.clear()
        binding.fuelTypeSpinner.setSelection(0)
        listOf(
            binding.forgotGasStationCheck, binding.forgotFuelLitersCheck,
            binding.forgotFuelCostCheck, binding.forgotFuelTypeCheck
        ).forEach { it.isChecked = false }
        binding.gasStationInput.isEnabled = true
        binding.fuelLitersInput.isEnabled = true
        binding.fuelCostInput.isEnabled = true
        binding.fuelTypeSpinner.isEnabled = true
        binding.tankFullRadio.isChecked = true
        listOf(
            binding.gasStationError, binding.fuelLitersError, binding.fuelCostError,
            binding.fuelTypeError, binding.destinationError, binding.mileageError
        ).forEach { it.visibility = View.GONE }
        if (!dateTimeLocked) {
            currentDate = Date()
            binding.dateBtn.text = dateFormat.format(currentDate)
        }
        updateEstimateCard()
    }

    private fun updateEstimateCard() {
        if (_binding == null) return
        if (records.isEmpty()) {
            binding.fuelEstimateCard.show(false)
            return
        }
        val estimated = Analytics.estimateTankCapacity(records)
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 } ?: estimated

        // 把表单里正在填的格数作为一条虚拟记录纳入估算，实现"边填边预览"
        val algoRecords = records.toMutableList()
        val formBefore = binding.gaugeBeforeInput.doubleOrNull()
        val formAfter = binding.gaugeAfterInput.doubleOrNull()
        val formNow = binding.gaugeNowInput.doubleOrNull()
        if (formBefore != null || formAfter != null || formNow != null) {
            algoRecords.add(
                FuelRecord(
                    id = "__form_preview__", date = Analytics.formatDateTime(Date()),
                    mileage = binding.mileageInput.intOrNull(),
                    isRefuel = binding.refuelCheck.isChecked,
                    gaugeNow = formNow,
                    refuelData = com.youjixingtu.app.model.RefuelData(
                        fuelLiters = binding.fuelLitersInput.doubleOrNull(),
                        fuelCost = binding.fuelCostInput.doubleOrNull(),
                        gaugeBefore = formBefore, gaugeAfter = formAfter
                    )
                )
            )
        }

        val gauge = Analytics.gaugeEstimate(
            algoRecords, Repository.gaugeTotal,
            Repository.gaugeCurrent, Repository.gaugeCurrentDate
        )

        binding.fuelEstimateCard.show(true)
        if (!binding.manualTankCapacityInput.text.isNullOrBlank()) {
            // 保留用户输入
        }

        if (gauge.usable) {
            // ===== 格数法（用户习惯：平时部分加油，越加越准）=====
            val remaining = gauge.remainingLiters!!
            val cap = gauge.capacityLiters ?: capacity.toDouble()
            binding.estimatedFuelText.text = "${formatNumber(remaining, 1)} 升"
            val pct = gauge.percent ?: ((remaining / cap * 100).toInt().coerceIn(0, 100))
            binding.fuelGauge.progress = pct
            binding.fuelPercentText.text = "$pct%"
            binding.remainingDistanceText.text = gauge.remainingKm?.let { "可行驶距离: $it 公里" }
                ?: "可行驶距离: -- 公里"
            binding.remainingValueText.text = gauge.remainingCost?.let { "当前油量价值: ${formatNumber(it, 1)} 元" }
                ?: "当前油量价值: -- 元"
            binding.estimatedTankCapacityText.text = "估算油箱容量: ${formatNumber(cap, 1)} 升（${gauge.totalBars} 格）"
            // 每格数据 + 样本数，体现"越用越准"
            val parts = mutableListOf<String>()
            gauge.litersPerBar?.let { parts.add("每格约 ${formatNumber(it, 2)} 升") }
            gauge.kmPerBar?.let { parts.add("${formatNumber(it, 1)} 公里/格") }
            gauge.costPerBar?.let { parts.add("${formatNumber(it, 1)} 元/格") }
            parts.add("已积累 ${gauge.sampleCount} 个格数样本，越多越准")
            binding.gaugeDetailText.visibility = View.VISIBLE
            binding.gaugeDetailText.text = parts.joinToString(" · ")
        } else {
            // ===== 旧算法兜底（无格数样本的历史记录）=====
            binding.gaugeDetailText.visibility = View.GONE
            binding.remainingValueText.text = "当前油量价值: -- 元"
            binding.estimatedTankCapacityText.text = "估算油箱容量: $estimated 升"
            val avg = Analytics.calculateFuelConsumption(records)
            if (avg == null) {
                binding.estimatedFuelText.text = "-- 升"
                binding.fuelGauge.progress = 0
                binding.fuelPercentText.text = "0%"
                binding.remainingDistanceText.text = "可行驶距离: -- 公里"
                return
            }
            val remaining = Analytics.calculateRemainingFuel(records, capacity)
            if (remaining == null) {
                binding.estimatedFuelText.text = "-- 升"
                binding.fuelGauge.progress = 0
                binding.fuelPercentText.text = "0%"
                binding.remainingDistanceText.text = "可行驶距离: -- 公里"
                return
            }
            binding.estimatedFuelText.text = "${formatNumber(remaining, 1)} 升"
            val percentage = ((remaining / capacity) * 100).coerceAtMost(100.0).toInt()
            binding.fuelGauge.progress = percentage
            binding.fuelPercentText.text = "$percentage%"
            val distance = remaining / avg * 100
            binding.remainingDistanceText.text = "可行驶距离: ${distance.toInt()} 公里"
        }
        if (binding.manualTankCapacityInput.text.isNullOrBlank()) {
            binding.manualTankCapacityInput.setText(capacity.toString())
        }
        if (binding.gaugeTotalInput.text.isNullOrBlank()) {
            binding.gaugeTotalInput.setText(Repository.gaugeTotal.toString())
        }
    }

    private fun updateMiniStats() {
        val stats = Analytics.dashboardStats(records)
        binding.avgConsumptionText.text = stats.avgConsumption?.let { formatNumber(it, 2) } ?: "--"
        binding.monthCostText.text = if (records.size >= 2) stats.monthCost.toInt().toString() else "--"
        binding.monthDistanceText.text = stats.monthDistance?.toString() ?: "--"
    }

}
