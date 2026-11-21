package com.youjixingtu.app.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.fragment.app.Fragment
import com.youjixingtu.app.R
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.FragmentRecordBinding
import com.youjixingtu.app.model.ForgottenFields
import com.youjixingtu.app.model.ForgottenFuelFields
import com.youjixingtu.app.model.FuelRecord
import com.youjixingtu.app.model.RefuelData
import com.youjixingtu.app.util.BluetoothAutoStartReceiver
import com.youjixingtu.app.util.BluetoothWatchService
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
    /** v1.15：BLUETOOTH_CONNECT 运行时权限（读连接中的蓝牙设备名需要） */
    private lateinit var btPermissionLauncher: ActivityResultLauncher<String>
    /** v1.15：保存蓝牙名时先记下来，授权成功后再落盘并启用 */
    private var pendingBtName: String? = null
    /** v1.17：蓝牙权限授权后要弹出的动作（选择设备对话框） */
    private var pendingBtAction: String? = null
    /** v1.18：通知权限（前台服务保活需要） */
    private lateinit var notifPermissionLauncher: ActivityResultLauncher<String>
    /** v1.18：申请通知权限后是否要启动保活服务 */
    private var pendingStartWatch = false
    /** v1.19：程序化回显总开关时抑制其监听，避免误触发停/启服务 */
    private var suppressBtMaster = false

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
        notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 通知权限给不给都尝试启动服务：Android 12 以下无需该权限；
            // 未授予时前台服务仍运行，只是通知可能不显示（不影响保活与广播接收）
            if (pendingStartWatch) {
                pendingStartWatch = false
                BluetoothWatchService.start(requireContext())
                Repository.btKeepAlive = true
                refreshBluetoothCard()
            }
        }
        btPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingBtAction
            pendingBtAction = null
            if (action == "pick") {
                // v1.17：从「选择设备」进来，授权后弹出已配对设备列表
                if (granted || Build.VERSION.SDK_INT < 31) showPairedDeviceDialog()
                else requireContext().toast("未授予蓝牙权限，无法读取已配对设备；可再次点「选择设备」重试")
                return@registerForActivityResult
            }
            val name = pendingBtName ?: return@registerForActivityResult
            if (granted || Build.VERSION.SDK_INT < 31) {
                persistBtName(name)
            } else {
                requireContext().toast("未授予蓝牙权限，无法识别连接的蓝牙名称；可再次点「保存」重试")
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
        refreshBluetoothCard()
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

        // ===== v1.15 车载蓝牙自动启动 =====
        binding.btSaveBtn.setOnClickListener {
            val name = binding.btNameInput.text.toString().trim()
            if (name.isEmpty()) {
                requireContext().toast("请先填写车机蓝牙的名称，或点「选择设备」")
                return@setOnClickListener
            }
            if (!hasBtPermission()) {
                pendingBtName = name
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return@setOnClickListener
            }
            persistBtName(name)
        }
        binding.btPickDeviceBtn.setOnClickListener {
            if (!hasBtPermission()) {
                pendingBtAction = "pick"
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return@setOnClickListener
            }
            showPairedDeviceDialog()
        }
        binding.btTestBtn.setOnClickListener {
            requireContext().toast(BluetoothAutoStartReceiver.diagnose(requireContext()), long = true)
        }
        // v1.19：整套方案总开关——关掉后本软件完全不插手、不耗电，交给手机系统自带功能
        binding.btMasterCheck.setOnCheckedChangeListener { _, checked ->
            if (suppressBtMaster) return@setOnCheckedChangeListener
            Repository.btUseOwnSolution = checked
            if (!checked) {
                BluetoothWatchService.stop(requireContext())
                requireContext().toast("已交给手机系统自带功能：本软件不再自动打开，也不再后台耗电")
            } else {
                if (Repository.btAutoStartEnabled && Repository.btKeepAlive) {
                    BluetoothWatchService.start(requireContext())
                    requireContext().toast("已启用本软件的自动打开方案")
                } else {
                    requireContext().toast("已启用；请再勾选下方「启用」和「后台保活」并选好设备")
                }
            }
            refreshBluetoothCard()
        }
        binding.btKeepAliveCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // Android 13+ 前台服务通知需要 POST_NOTIFICATIONS
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingStartWatch = true
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    BluetoothWatchService.start(requireContext())
                    Repository.btKeepAlive = true
                    requireContext().toast("后台保活已开启，通知栏会常驻一条通知（请勿手动划掉）")
                }
            } else {
                BluetoothWatchService.stop(requireContext())
                Repository.btKeepAlive = false
                requireContext().toast("后台保活已关闭，系统可能冻结本软件导致蓝牙自动打开失效")
            }
            refreshBluetoothCard()
        }
        binding.btEnabledCheck.setOnCheckedChangeListener { _, checked ->
            if (Repository.btDeviceName.isBlank()) {
                if (checked) {
                    requireContext().toast("请先填写蓝牙名称并保存")
                }
                binding.btEnabledCheck.isChecked = false
                return@setOnCheckedChangeListener
            }
            Repository.btAutoStartEnabled = checked
        }
        binding.btOverlayBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(requireContext())) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                }.onFailure { requireContext().toast("请到系统设置里允许本软件「显示在其他应用上层」") }
            } else {
                requireContext().toast("悬浮窗权限已授予，无需再设置")
            }
        }
    }

    /** Android 12+ 读蓝牙设备列表/名称需要 BLUETOOTH_CONNECT */
    private fun hasBtPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** v1.17：保存名称时，尽量从已配对设备里找到同名设备的 MAC 一并存下（MAC 匹配最可靠） */
    private fun persistBtName(name: String) {
        var addr = ""
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.bondedDevices?.firstOrNull { device ->
                val n = runCatching { device.name }.getOrNull().orEmpty()
                n.equals(name, ignoreCase = true) || n.contains(name, ignoreCase = true) ||
                    name.contains(n, ignoreCase = true)
            }?.let { addr = it.address }
        }
        Repository.btDeviceName = name
        if (addr.isNotEmpty()) Repository.btDeviceAddress = addr
        binding.btNameInput.setText(name)
        Repository.btAutoStartEnabled = true
        requireContext().toast("已保存：连接「$name」后自动打开本软件")
        refreshBluetoothCard()
    }

    /** v1.17：列出系统里已配对的蓝牙设备，点选后自动保存名称 + MAC，免去手打名字对不上 */
    private fun showPairedDeviceDialog() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            requireContext().toast("没有已配对设备，请先在手机系统蓝牙里配对车机后再来")
            return
        }
        val sorted = devices.sortedBy { runCatching { it.name }.getOrNull().orEmpty() }
        val labels = sorted.map { device ->
            val n = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "未命名设备" }
            val a = runCatching { device.address }.getOrNull().orEmpty()
            "$n  ($a)"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择车机蓝牙")
            .setItems(labels) { _, index ->
                val device: BluetoothDevice = sorted[index]
                val n = runCatching { device.name }.getOrNull().orEmpty()
                val a = runCatching { device.address }.getOrNull().orEmpty()
                if (n.isNotBlank()) Repository.btDeviceName = n
                if (a.isNotBlank()) Repository.btDeviceAddress = a
                if (n.isNotBlank()) binding.btNameInput.setText(n)
                Repository.btAutoStartEnabled = true
                requireContext().toast("已选择：${n.ifBlank { a }}")
                refreshBluetoothCard()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** v1.15：把蓝牙卡片回显到当前设置状态，并给出待办提示 */
    private fun refreshBluetoothCard() {
        val name = Repository.btDeviceName
        if (binding.btNameInput.text.isNullOrBlank() && name.isNotBlank()) {
            binding.btNameInput.setText(name)
        }
        val useOwn = Repository.btUseOwnSolution
        suppressBtMaster = true
        binding.btMasterCheck.isChecked = useOwn
        suppressBtMaster = false
        binding.btEnabledCheck.isChecked = Repository.btAutoStartEnabled && name.isNotBlank()
        binding.btKeepAliveCheck.isChecked = Repository.btKeepAlive
        // 总开关关闭时把下面的子项置灰，表示本方案整体不生效
        val subEnabled = useOwn
        binding.btNameInput.isEnabled = subEnabled
        binding.btSaveBtn.isEnabled = subEnabled
        binding.btPickDeviceBtn.isEnabled = subEnabled
        binding.btTestBtn.isEnabled = subEnabled
        binding.btEnabledCheck.isEnabled = subEnabled
        binding.btKeepAliveCheck.isEnabled = subEnabled
        binding.btOverlayBtn.isEnabled = subEnabled

        val tips = mutableListOf<String>()
        if (!useOwn) {
            // v1.19：总开关关闭——交给系统自带功能，本软件不耗电
            binding.btStatusText.text = "已关闭：连接蓝牙后本软件不会自动打开，也不再后台耗电，" +
                "交给你手机系统自带的蓝牙功能处理。需要本软件接管时，勾选上方总开关。"
        } else {
            // v1.18：保活是关键——国产 ROM 会冻结后台进程导致广播被丢弃，必须开
            if (!Repository.btKeepAlive) {
                tips.add("① 勾选「后台保活」（不开则系统冻结后收不到蓝牙广播，自动打开必失效）")
            }
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(requireContext())) {
                tips.add("② 点「授权悬浮窗」允许显示在其他应用上层（后台自动打开必需）")
            }
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                tips.add("③ 点「保存」后按提示允许「附近的设备」权限（识别蓝牙名必需）")
            }
            val addr = Repository.btDeviceAddress
            if (Repository.btAutoStartEnabled && name.isNotBlank() && Repository.btKeepAlive && tips.isEmpty()) {
                binding.btStatusText.text = "已启用：连接「$name」后自动打开" +
                    (if (addr.isNotBlank()) "（已按 MAC 识别）" else "") +
                    "，后台保活运行中。若没生效，请到系统设置里允许本软件「自启动」"
            } else if (tips.isNotEmpty()) {
                binding.btStatusText.text = "待完成：\n" + tips.joinToString("\n")
            } else {
                binding.btStatusText.text = ""
            }
        }
        binding.btStatusText.visibility =
            if (!useOwn) View.VISIBLE
            else if (tips.isEmpty() && name.isBlank()) View.GONE
            else View.VISIBLE

        // v1.17：显示最近一次蓝牙广播的判定结果，出问题能直接看出卡在哪一步
        val lastEvent = Repository.btLastEvent
        binding.btDiagText.text = if (lastEvent.isNotBlank()) "最近一次蓝牙事件：$lastEvent" else ""
        binding.btDiagText.visibility = if (lastEvent.isNotBlank()) View.VISIBLE else View.GONE
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
