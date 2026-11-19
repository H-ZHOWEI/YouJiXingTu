package com.youjixingtu.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.ActivityEditRecordBinding
import com.youjixingtu.app.model.ForgottenFields
import com.youjixingtu.app.model.ForgottenFuelFields
import com.youjixingtu.app.model.RefuelData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EditRecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditRecordBinding
    private var recordId = ""
    private lateinit var records: MutableList<com.youjixingtu.app.model.FuelRecord>
    private var currentDate = Date()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
    private lateinit var nearbyStationLauncher: ActivityResultLauncher<Intent>
    /** 程序化设置勾选时抑制弹页（A1） */
    private var suppressStationPrompt = false
    /** 「我忘记了」原值暂存（C4） */
    private val forgotSavedValues = mutableMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nearbyStationLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val station = result.data?.getStringExtra(NearbyStationActivity.EXTRA_STATION)?.trim().orEmpty()
                if (station.isNotBlank()) applyPickedStation(station)
            }
        }

        recordId = intent.getStringExtra("record_id") ?: ""
        records = Repository.loadRecords().toMutableList()
        val record = records.firstOrNull { it.id == recordId }
        if (record == null) {
            toast("未找到该记录")
            finish()
            return
        }

        currentDate = Analytics.parseDate(record.date)
        binding.editDateBtn.text = dateFormat.format(currentDate)

        val forgotMileage = record.mileageForgotten
        binding.editForgotMileageCheck.isChecked = forgotMileage
        binding.editMileageInput.isEnabled = !forgotMileage
        if (!forgotMileage) binding.editMileageInput.setText(record.mileage?.toString() ?: "")

        binding.editDestinationInput.setText(record.destination)
        binding.editRefuelCheck.isChecked = record.isRefuel
        binding.editFuelDetailsLayout.show(record.isRefuel)

        val fuel = record.refuelData
        if (fuel != null) {
            val ff = fuel.forgottenFields
            binding.editGasStationInput.setText(fuel.gasStation ?: "")
            binding.editFuelLitersInput.setText(fuel.fuelLiters?.toString() ?: "")
            binding.editFuelCostInput.setText(fuel.fuelCost?.toString() ?: "")
            binding.editFuelTypeSpinner.setSelection(fuelTypePosition(this, fuel.fuelType))

            binding.editForgotGasStationCheck.isChecked = ff?.gasStation == true
            binding.editForgotFuelLitersCheck.isChecked = ff?.fuelLiters == true
            binding.editForgotFuelCostCheck.isChecked = ff?.fuelCost == true
            binding.editForgotFuelTypeCheck.isChecked = ff?.fuelType == true

            binding.editGasStationInput.isEnabled = ff?.gasStation != true
            binding.editFuelLitersInput.isEnabled = ff?.fuelLiters != true
            binding.editFuelCostInput.isEnabled = ff?.fuelCost != true
            binding.editFuelTypeSpinner.isEnabled = ff?.fuelType != true
        }

        if (record.tankStatus == "partial") binding.editTankPartialRadio.isChecked = true

        // I15：常用目的地补全
        val dest = binding.editDestinationInput as AutoCompleteTextView
        dest.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Analytics.frequentDestinations(records)))

        setupListeners()
    }

    /** 将选中的加油站填入「加油站名称」与「去了哪里」 */
    private fun applyPickedStation(station: String) {
        binding.editGasStationInput.setText(station)
        if (binding.editDestinationInput.text.isNullOrBlank()) binding.editDestinationInput.setText(station)
        Repository.lastGasStation = station
        toast("已填入：$station，可修改", long = true)
    }

    private fun setupListeners() {
        binding.editDateBtn.setOnClickListener { showDatePicker() }

        setupForgot(binding.editForgotMileageCheck, binding.editMileageInput, binding.editMileageError)
        setupForgot(binding.editForgotGasStationCheck, binding.editGasStationInput, binding.editGasStationError)
        setupForgot(binding.editForgotFuelLitersCheck, binding.editFuelLitersInput, binding.editFuelLitersError)
        setupForgot(binding.editForgotFuelCostCheck, binding.editFuelCostInput, binding.editFuelCostError)
        setupForgot(binding.editForgotFuelTypeCheck, binding.editFuelTypeSpinner, binding.editFuelTypeError)

        binding.editRefuelCheck.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            binding.editFuelDetailsLayout.show(checked)
            // A1/A3：仅用户主动勾选且加油站为空时弹站，避免重复/误触发弹页
            if (checked && !suppressStationPrompt && binding.editGasStationInput.text.isNullOrBlank()) {
                val intent = Intent(this, NearbyStationActivity::class.java)
                nearbyStationLauncher.launch(intent)
            }
        }
        binding.editGasStationInput.addTextChangedListener(simpleTextWatcher { text ->
            // A5：仅目的地为空时带入站名，不覆盖用户已填内容
            if (binding.editRefuelCheck.isChecked && binding.editDestinationInput.text.isNullOrBlank()) {
                binding.editDestinationInput.setText(text)
            }
            // I2：该站历史常用油品自动带出（当前为默认项时）
            val station = text.toString().trim()
            if (station.isNotBlank()) {
                Analytics.stationFuelType(records, station)?.let { type ->
                    val pos = fuelTypePosition(this@EditRecordActivity, type)
                    if (pos > 0 && binding.editFuelTypeSpinner.selectedItemPosition == 0) {
                        binding.editFuelTypeSpinner.setSelection(pos)
                    }
                }
            }
        })
        // I1：按加油量推断是否加满
        binding.editFuelLitersInput.addTextChangedListener(simpleTextWatcher {
            val liters = binding.editFuelLitersInput.doubleOrNull()
            val capacity = Repository.tankCapacity.takeIf { it in 20..200 }
                ?: Analytics.estimateTankCapacity(records)
            when (Analytics.inferTankStatus(liters, capacity)) {
                "full" -> binding.editTankFullRadio.isChecked = true
                "partial" -> binding.editTankPartialRadio.isChecked = true
                else -> {}
            }
        })
        // A4：移除金额自动判满逻辑，油箱状态完全手动
        binding.editFuelTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = fuelTypeValue(this@EditRecordActivity, position)
                if (value.isNotBlank()) Repository.lastFuelType = value
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.cancelEditBtn.setOnClickListener { finish() }
        binding.saveEditBtn.setOnClickListener { save() }
    }

    private fun setupForgot(check: CompoundButton, input: View, error: View) {
        check.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            input.isEnabled = !checked
            error.visibility = View.GONE
            if (input is android.widget.EditText) {
                if (checked) {
                    forgotSavedValues[input.id] = input.text?.toString().orEmpty()
                    input.text?.clear()
                } else {
                    val saved = forgotSavedValues.remove(input.id)
                    if (saved != null) input.setText(saved)
                }
            }
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        cal.time = currentDate
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        currentDate = cal.time
                        binding.editDateBtn.text = dateFormat.format(currentDate)
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save() {
        val index = records.indexOfFirst { it.id == recordId }
        if (index < 0) return
        val original = records[index]

        val forgotMileage = binding.editForgotMileageCheck.isChecked
        val mileage = if (forgotMileage) null else binding.editMileageInput.intOrNull()
        if (!forgotMileage && mileage == null) {
            toast("请填写当前油表公里数或选择\"我忘记了\"")
            return
        }
        if (mileage != null) {
            val validation = Analytics.validateMileage(records, currentDate, mileage, recordId)
            if (!validation.ok) {
                binding.editMileageError.text = validation.message
                binding.editMileageError.visibility = View.VISIBLE
                return
            }
        }
        binding.editMileageError.visibility = View.GONE

        val destination = binding.editDestinationInput.text.toString().trim()
        if (destination.isBlank()) {
            binding.editDestinationError.visibility = View.VISIBLE
            return
        }
        binding.editDestinationError.visibility = View.GONE

        val refuel = binding.editRefuelCheck.isChecked
        val station = binding.editGasStationInput.text.toString().trim()
        val liters = binding.editFuelLitersInput.doubleOrNull()
        val cost = binding.editFuelCostInput.doubleOrNull()

        // I3：重复记录检测
        Analytics.detectDuplicateHint(records, currentDate, mileage, refuel, recordId)?.let { hint ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("可能是重复记录")
                .setMessage("$hint\n\n如果确实要记录，请点「仍然保存」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然保存") { _, _ -> persistEdit(index, original, mileage, destination, refuel, station, liters, cost) }
                .show()
            return
        }
        // I4：里程量级异常提醒
        if (mileage != null) {
            Analytics.mileageAnomalyHint(records, currentDate, mileage, recordId)?.let { hint ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("请确认公里数")
                    .setMessage("$hint\n\n确认无误请点「继续保存」。")
                    .setNegativeButton("返回修改", null)
                    .setPositiveButton("继续保存") { _, _ -> persistEdit(index, original, mileage, destination, refuel, station, liters, cost) }
                    .show()
                return
            }
        }
        persistEdit(index, original, mileage, destination, refuel, station, liters, cost)
    }

    /** 真正落盘（从 save() 抽出，供智能提示确认后复用） */
    private fun persistEdit(
        index: Int, original: com.youjixingtu.app.model.FuelRecord,
        mileage: Int?, destination: String, refuel: Boolean, station: String,
        liters: Double?, cost: Double?
    ) {
        val fuelType = fuelTypeValue(this, binding.editFuelTypeSpinner.selectedItemPosition)
        val forgotStation = binding.editForgotGasStationCheck.isChecked
        val forgotLiters = binding.editForgotFuelLitersCheck.isChecked
        val forgotCost = binding.editForgotFuelCostCheck.isChecked
        val forgotType = binding.editForgotFuelTypeCheck.isChecked

        if (refuel) {
            if (!forgotStation && station.isBlank()) {
                binding.editGasStationError.visibility = View.VISIBLE
                return
            }
            if (!forgotLiters && binding.editFuelLitersInput.text.isNullOrBlank()) {
                binding.editFuelLitersError.visibility = View.VISIBLE
                return
            }
            if (!forgotCost && binding.editFuelCostInput.text.isNullOrBlank()) {
                binding.editFuelCostError.visibility = View.VISIBLE
                return
            }
            if (!forgotType && fuelType.isBlank()) {
                binding.editFuelTypeError.visibility = View.VISIBLE
                return
            }
        }

        val hasFuelData = refuel || station.isNotBlank() || liters != null || cost != null || fuelType.isNotBlank()
        val updated = original.copy(
            date = dateFormat.format(currentDate),
            timestamp = currentDate.time, // A2：日期改动后同步刷新时间戳，保证「最近加油站」派生准确
            mileage = mileage,
            destination = destination,
            isRefuel = refuel,
            tankStatus = if (binding.editTankFullRadio.isChecked) "full" else "partial",
            forgottenFields = ForgottenFields(mileage = binding.editForgotMileageCheck.isChecked),
            refuelData = if (hasFuelData) RefuelData(
                gasStation = if (forgotStation) null else station.ifBlank { null },
                fuelLiters = if (forgotLiters) null else liters,
                fuelCost = if (forgotCost) null else cost,
                fuelType = if (forgotType) null else fuelType.ifBlank { null },
                forgottenFields = ForgottenFuelFields(forgotStation, forgotLiters, forgotCost, forgotType)
            ) else null
        )
        records[index] = updated
        if (!Repository.saveRecords(records)) {
            toast("保存失败，请重试")
            return
        }
        if (!forgotStation && station.isNotBlank()) Repository.lastGasStation = station
        toast("星迹记录修改成功！")
        setResult(Activity.RESULT_OK)
        finish()
    }
}
