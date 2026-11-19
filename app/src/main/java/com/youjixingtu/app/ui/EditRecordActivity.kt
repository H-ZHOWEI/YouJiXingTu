package com.youjixingtu.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            binding.editFuelTypeSpinner.setSelection(fuelTypePosition(fuel.fuelType))

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

        setupListeners()
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
            if (checked) {
                val lastStation = Repository.lastGasStation
                if (binding.editGasStationInput.text?.isNotBlank() != true && lastStation.isNotBlank()) {
                    binding.editGasStationInput.setText(lastStation)
                    toast("已填入最近加油站：$lastStation，可修改")
                }
                if (binding.editDestinationInput.text?.isBlank() == true && binding.editGasStationInput.text?.isNotBlank() == true) {
                    binding.editDestinationInput.setText(binding.editGasStationInput.text.toString())
                }
            }
        }
        binding.editGasStationInput.addTextChangedListener(simpleTextWatcher { text ->
            if (binding.editRefuelCheck.isChecked) binding.editDestinationInput.setText(text)
        })
        binding.editFuelCostInput.addTextChangedListener(simpleTextWatcher { text ->
            val cost = text.toDoubleOrNull()
            if (cost != null) {
                val full = cost > 200 && cost % 1 != 0.0
                binding.editTankFullRadio.isChecked = full
                binding.editTankPartialRadio.isChecked = !full
            }
        })
        binding.editFuelTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = fuelTypeValue(position)
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
            if (input is android.widget.EditText && checked) input.text?.clear()
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
        val fuelType = fuelTypeValue(binding.editFuelTypeSpinner.selectedItemPosition)
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
            mileage = mileage,
            destination = destination,
            isRefuel = refuel,
            tankStatus = if (binding.editTankFullRadio.isChecked) "full" else "partial",
            forgottenFields = ForgottenFields(mileage = forgotMileage),
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
