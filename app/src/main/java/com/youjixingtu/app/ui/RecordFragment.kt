package com.youjixingtu.app.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.youjixingtu.app.R
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.FragmentRecordBinding
import com.youjixingtu.app.model.CameraPhoto
import com.youjixingtu.app.model.ForgottenFields
import com.youjixingtu.app.model.ForgottenFuelFields
import com.youjixingtu.app.model.FuelRecord
import com.youjixingtu.app.model.RefuelData
import java.io.File
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
    private var currentPhotoPath: String? = null
    /** 程序化设置 refuelCheck 时抑制弹页（A1：OCR 自动填充不应触发附近站选择） */
    private var suppressStationPrompt = false
    /** 「我忘记了」勾选前的原值，取消勾选时还原（C4） */
    private val forgotSavedValues = mutableMapOf<Int, String>()
    /** I13：空数据引导只提示一次 */
    private var hasShownEmptyGuide = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var ocrLauncher: ActivityResultLauncher<Intent>
    private lateinit var nearbyStationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else requireContext().toast("需要相机权限才能拍照，可使用上传照片")
        }
        takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && currentPhotoPath != null) onPhotoReady(currentPhotoPath!!)
        }
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) copyGalleryImage(uri)
        }
        ocrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                applyOcrResult(
                    data.getStringExtra("station"),
                    data.getDoubleExtra("liters", -1.0).takeIf { it >= 0 },
                    data.getDoubleExtra("cost", -1.0).takeIf { it >= 0 },
                    data.getStringExtra("type")
                )
            }
        }
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
        showLastPhoto()
        updateSmartHint()
        // I13：首次进入且无任何记录时给一次上手引导
        if (records.isEmpty() && !hasShownEmptyGuide) {
            hasShownEmptyGuide = true
            requireContext().toast("欢迎使用！先拍张小票或手动记一笔，记满 2 次加油就能自动算油耗", long = true)
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
        val remaining = Analytics.calculateRemainingFuel(records, capacity)
        if (remaining != null) {
            val pct = (remaining / capacity * 100).toInt()
            if (pct <= 15) {
                smartHint("⚠ 当前油量仅剩约 $pct%，建议尽快加油；勾选「本次加油了」可一键查找附近加油站。")
                return
            }
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

        binding.takePhotoBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        binding.uploadPhotoBtn.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.retakePhotoBtn.setOnClickListener {
            binding.cameraPreview.show(false)
            binding.takePhotoBtn.performClick()
        }
        binding.analyzePhotoBtn.setOnClickListener { launchOcr() }
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

        // I3：重复记录检测 —— 用户确认后才继续
        Analytics.detectDuplicateHint(records, currentDate, mileage, refuel)?.let { hint ->
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("可能是重复记录")
                .setMessage("$hint\n\n如果确实要记录，请点「仍然保存」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然保存") { _, _ -> persistRecord(mileage, destination, refuel, station, liters, cost) }
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
                    .setPositiveButton("继续保存") { _, _ -> persistRecord(mileage, destination, refuel, station, liters, cost) }
                    .show()
                return
            }
        }
        persistRecord(mileage, destination, refuel, station, liters, cost)
    }

    /** 真正落盘（从 saveRecord 抽出，供智能提示确认后复用） */
    private fun persistRecord(
        mileage: Int?, destination: String, refuel: Boolean, station: String,
        liters: Double?, cost: Double?
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
        val hasFuelData = refuel || station.isNotBlank() || liters != null || cost != null || fuelType.isNotBlank()
        val record = FuelRecord(
            id = Analytics.newId(),
            date = dateFormat.format(currentDate),
            timestamp = System.currentTimeMillis(),
            mileage = mileage,
            destination = destination,
            isRefuel = refuel,
            tankStatus = tankStatus,
            forgottenFields = ForgottenFields(mileage = forgotMileage),
            refuelData = if (hasFuelData) RefuelData(
                gasStation = if (forgotStation) null else station.ifBlank { null },
                fuelLiters = if (forgotLiters) null else liters,
                fuelCost = if (forgotCost) null else cost,
                fuelType = if (forgotType) null else fuelType.ifBlank { null },
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
        if (records.isEmpty()) {
            binding.fuelEstimateCard.show(false)
            return
        }
        val avg = Analytics.calculateFuelConsumption(records)
        if (avg == null) {
            binding.fuelEstimateCard.show(false)
            return
        }
        binding.fuelEstimateCard.show(true)

        val estimated = Analytics.estimateTankCapacity(records)
        val capacity = Repository.tankCapacity.takeIf { it in 20..200 } ?: estimated
        binding.estimatedTankCapacityText.text = "估算油箱容量: $estimated 升"
        if (binding.manualTankCapacityInput.text.isNullOrBlank()) {
            binding.manualTankCapacityInput.setText(capacity.toString())
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

    private fun updateMiniStats() {
        val stats = Analytics.dashboardStats(records)
        binding.avgConsumptionText.text = stats.avgConsumption?.let { formatNumber(it, 2) } ?: "--"
        binding.monthCostText.text = if (records.size >= 2) stats.monthCost.toInt().toString() else "--"
        binding.monthDistanceText.text = stats.monthDistance?.toString() ?: "--"
    }

    private fun launchCamera() {
        val dir = File(requireContext().filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        currentPhotoPath = file.absolutePath
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        takePictureLauncher.launch(uri)
    }

    private fun copyGalleryImage(uri: Uri) {
        try {
            val dir = File(requireContext().filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "gallery_${System.currentTimeMillis()}.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            onPhotoReady(file.absolutePath)
        } catch (e: Exception) {
            requireContext().toast("读取照片失败：${e.message ?: "未知错误"}")
        }
    }

    private fun onPhotoReady(path: String) {
        val photos = Repository.loadPhotos()
        if (photos.size >= 10) {
            // B4：移除列表时同步删除实际图片文件，避免 filesDir/photos 无限累积
            val removed = photos.removeAt(0)
            runCatching { File(removed.path).delete() }
        }
        photos.add(CameraPhoto(System.currentTimeMillis(), path, dateFormat.format(Date())))
        Repository.savePhotos(photos)
        currentPhotoPath = path
        binding.photoPreview.setImageURI(Uri.fromFile(File(path)))
        binding.cameraPreview.show(true)
    }

    private fun showLastPhoto() {
        val last = Repository.loadPhotos().lastOrNull()
        if (last != null && File(last.path).exists()) {
            currentPhotoPath = last.path
            binding.photoPreview.setImageURI(Uri.fromFile(File(last.path)))
            binding.cameraPreview.show(true)
        }
    }

    private fun launchOcr() {
        val path = currentPhotoPath
        if (path == null || !File(path).exists()) {
            requireContext().toast("请先拍摄或上传照片")
            return
        }
        val intent = Intent(requireContext(), OcrActivity::class.java).apply {
            putExtra("photo_path", path)
        }
        ocrLauncher.launch(intent)
    }

    private fun applyOcrResult(station: String?, liters: Double?, cost: Double?, type: String?) {
        // A1：程序化勾选时抑制附近站弹页，OCR 结果不应被打断
        suppressStationPrompt = true
        binding.refuelCheck.isChecked = true
        suppressStationPrompt = false
        binding.fuelDetailsLayout.show(true)
        if (!station.isNullOrBlank()) {
            binding.gasStationInput.setText(station)
            if (binding.destinationInput.text.isNullOrBlank()) binding.destinationInput.setText(station)
        }
        if (liters != null) binding.fuelLitersInput.setText(liters.toString())
        if (cost != null) binding.fuelCostInput.setText(cost.toString())
        if (!type.isNullOrBlank()) binding.fuelTypeSpinner.setSelection(fuelTypePosition(requireContext(), type))
        // A4：油箱状态不再由金额推断，保持默认「加满」，用户可自行改
        requireContext().toast("信息已自动填充，请检查并完善其他字段", long = true)
    }
}
