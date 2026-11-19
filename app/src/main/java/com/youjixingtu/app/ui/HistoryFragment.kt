package com.youjixingtu.app.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.DialogTextIoBinding
import com.youjixingtu.app.databinding.FragmentHistoryBinding
import com.youjixingtu.app.model.FuelRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private var records = mutableListOf<FuelRecord>()
    private var currentPage = 1
    private val pageSize = 10
    private var pendingExportText = ""

    private lateinit var editLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveJsonLauncher: ActivityResultLauncher<String>
    private lateinit var openJsonLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refresh()
        }
        saveJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeExportFile(uri, pendingExportText)
        }
        openJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readAndImport(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.historyRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.deleteAllBtn.setOnClickListener {
            confirm("清除星迹", "您确定要清除所有星迹吗？此操作不可恢复。") {
                // B4：清空记录时同步删除照片文件与照片列表
                Repository.loadPhotos().forEach { runCatching { File(it.path).delete() } }
                Repository.savePhotos(emptyList())
                records.clear()
                Repository.saveRecords(records)
                Repository.invalidateCache()
                currentPage = 1
                refresh()
                requireContext().toast("所有星迹已清除")
            }
        }
        binding.exportBtn.setOnClickListener { showExportDialog() }
        binding.importBtn.setOnClickListener { showImportDialog() }
        binding.cleanRedundantBtn.setOnClickListener { cleanRedundant() }
        binding.prevPageBtn.setOnClickListener {
            if (currentPage > 1) { currentPage--; refresh() }
        }
        binding.nextPageBtn.setOnClickListener {
            if (currentPage < totalPages()) { currentPage++; refresh() }
        }
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
        records = Repository.loadRecords().sortedByDescending { Analytics.parseDate(it.date) }.toMutableList()
        val pages = totalPages()
        if (currentPage > pages) currentPage = pages
        val start = (currentPage - 1) * pageSize
        val pageItems = records.drop(start).take(pageSize)

        binding.historyRecycler.adapter = RecordAdapter(pageItems, { record ->
            val intent = Intent(requireContext(), EditRecordActivity::class.java).putExtra("record_id", record.id)
            editLauncher.launch(intent)
        }, { record ->
            confirm("清除星迹", "您确定要清除这条星迹吗？此操作不可恢复。") {
                records.removeAll { it.id == record.id }
                Repository.saveRecords(records)
                refresh()
                requireContext().toast("星迹已清除")
            }
        })

        binding.pageInfoText.text = "第 $currentPage 页 / 共 $pages 页"
        binding.prevPageBtn.isEnabled = currentPage > 1
        binding.nextPageBtn.isEnabled = currentPage < pages
        binding.recordCountText.text = records.size.toString()
        val sizeKb = Repository.recordsJson().toByteArray().size / 1024.0
        binding.storageUsageText.text = String.format(Locale.getDefault(), "%.2f KB", sizeKb)
    }

    private fun totalPages(): Int = maxOf(1, ceil(records.size.toDouble() / pageSize).toInt())

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> onYes() }
            .show()
    }

    private fun cleanRedundant() {
        if (records.isEmpty()) {
            requireContext().toast("没有记录可以清理")
            return
        }
        val ids = Analytics.redundantRecordIds(records)
        if (ids.isEmpty()) {
            requireContext().toast("当前没有可清理的冗余记录")
            return
        }
        confirm("清理冗余记录", "此操作将清理 ${ids.size} 条冗余记录，保留关键数据用于统计。确定要继续吗？") {
            val original = records.size
            records.removeAll { ids.contains(it.id) }
            Repository.saveRecords(records)
            refresh()
            requireContext().toast("清理完成！删除了 ${original - records.size} 条，保留了 ${records.size} 条关键记录")
        }
    }

    private fun showExportDialog() {
        if (records.isEmpty()) {
            requireContext().toast("没有星迹数据可以导出")
            return
        }
        val json = Repository.recordsJson()
        showTextDialog(
            title = "导出星图数据",
            hint = "可复制文本，或保存为 JSON 文件",
            initial = json,
            primaryLabel = "复制",
            secondaryLabel = "保存到文件",
            onPrimary = { text ->
                copyToClipboard(text)
                requireContext().toast("星图数据已复制到剪贴板")
            },
            onSecondary = {
                pendingExportText = json
                val name = "youjixingtu_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.json"
                saveJsonLauncher.launch(name)
            }
        )
    }

    private fun showImportDialog() {
        showTextDialog(
            title = "导入星图数据",
            hint = "粘贴之前导出的 JSON，或从文件选择",
            initial = "",
            primaryLabel = "导入数据",
            secondaryLabel = "从文件选择",
            onPrimary = { text -> importFromText(text) },
            onSecondary = { openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        )
    }

    private fun showTextDialog(
        title: String,
        hint: String,
        initial: String,
        primaryLabel: String,
        secondaryLabel: String,
        onPrimary: (String) -> Unit,
        onSecondary: () -> Unit
    ) {
        val dialog = Dialog(requireContext())
        val db = DialogTextIoBinding.inflate(layoutInflater)
        dialog.setContentView(db.root)
        // 限制弹窗宽度为屏宽-32dp、高度最大为屏高 80%，保证底部按钮永远在屏幕内
        dialog.window?.let { w ->
            val dm = resources.displayMetrics
            val lp = w.attributes
            lp.width = (dm.widthPixels - 32 * dm.density).toInt()
            lp.height = (dm.heightPixels * 0.8f).toInt()
            w.attributes = lp
        }
        db.dialogTitle.text = title
        db.dialogHint.text = hint
        db.dialogText.setText(initial)
        db.dialogPrimaryBtn.text = primaryLabel
        db.dialogSecondaryBtn.text = secondaryLabel
        db.dialogPrimaryBtn.setOnClickListener {
            onPrimary(db.dialogText.text.toString())
            dialog.dismiss()
        }
        db.dialogSecondaryBtn.setOnClickListener {
            onSecondary()
            dialog.dismiss()
        }
        db.dialogCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("油迹星图数据", text))
    }

    private fun writeExportFile(uri: Uri, text: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            requireContext().toast("星图数据已保存")
        } catch (e: Exception) {
            requireContext().toast("保存失败：${e.message ?: "未知错误"}")
        }
    }

    private fun readAndImport(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!text.isNullOrBlank()) importFromText(text)
        } catch (e: Exception) {
            requireContext().toast("读取文件失败：${e.message ?: "未知错误"}")
        }
    }

    private fun importFromText(text: String) {
        if (text.isBlank()) {
            requireContext().toast("请输入要导入的数据")
            return
        }
        try {
            val imported = Repository.parseRecordsJson(text)
            if (imported.isEmpty()) throw IllegalArgumentException("星图数据格式不正确")
            if (imported.any { it.id.isBlank() || it.date.isBlank() }) throw IllegalArgumentException("星图数据格式不正确")

            val existingIds = records.map { it.id }.toSet()
            val newRecords = imported.filter { !existingIds.contains(it.id) }
            if (newRecords.isEmpty()) {
                requireContext().toast("没有新的星迹记录可以导入")
                return
            }
            Analytics.normalizeImported(newRecords)
            records.addAll(newRecords)
            Repository.saveRecords(records)
            Repository.invalidateCache()
            refresh()
            requireContext().toast("成功导入 ${newRecords.size} 条星迹记录")
        } catch (e: Exception) {
            requireContext().toast("星图导入失败：${e.message ?: "格式不正确"}")
        }
    }
}
