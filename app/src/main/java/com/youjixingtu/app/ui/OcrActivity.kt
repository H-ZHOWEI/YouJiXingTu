package com.youjixingtu.app.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.youjixingtu.app.databinding.ActivityOcrBinding
import com.youjixingtu.app.util.TextExtractHelper
import java.io.File

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private var photoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoPath = intent.getStringExtra("photo_path")?.takeIf { it.isNotBlank() && File(it).exists() }
        if (photoPath != null) {
            binding.ocrPreview.setImageURI(Uri.fromFile(File(photoPath!!)))
        }

        binding.receiptText.setText("")
        binding.receiptText.hint = "正在识别小票文字…"

        binding.extractBtn.setOnClickListener { extract() }
        binding.cancelOcrBtn.setOnClickListener { finish() }
        binding.applyOcrBtn.setOnClickListener {
            val out = intent.apply {
                putExtra("station", binding.ocrStationInput.text.toString().trim().ifBlank { null })
                putExtra("liters", binding.ocrLitersInput.text.toString().toDoubleOrNull() ?: -1.0)
                putExtra("cost", binding.ocrCostInput.text.toString().toDoubleOrNull() ?: -1.0)
                putExtra("type", binding.ocrTypeInput.text.toString().trim().ifBlank { null })
            }
            setResult(Activity.RESULT_OK, out)
            finish()
        }

        if (photoPath != null) {
            runOcr(photoPath!!)
        } else {
            binding.receiptText.hint = "未找到照片，请手动输入小票文字"
            toast("未找到照片，可直接手动填写")
        }
    }

    /** 对照片做真实的本地中文 OCR 识别 */
    private fun runOcr(path: String) {
        binding.extractBtn.isEnabled = false
        try {
            val image = InputImage.fromFilePath(this, Uri.fromFile(File(path)))
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    if (text.isNullOrBlank()) {
                        binding.receiptText.setText("")
                        binding.receiptText.hint = "未识别到文字，请照着小票手动输入"
                        toast("未识别到文字，请手动输入后点「从文本提取」")
                    } else {
                        binding.receiptText.setText(text)
                        extractInternal(showToast = false)
                        toast("识别完成，请核对下方信息")
                    }
                    binding.extractBtn.isEnabled = true
                }
                .addOnFailureListener {
                    binding.receiptText.hint = "识别失败，请手动输入小票文字"
                    binding.extractBtn.isEnabled = true
                    toast("识别失败，请手动输入后点「从文本提取」")
                }
        } catch (e: Exception) {
            binding.receiptText.hint = "无法读取照片，请手动输入"
            binding.extractBtn.isEnabled = true
            toast("无法读取照片，请手动填写")
        }
    }

    private fun extract() = extractInternal(showToast = true)

    private fun extractInternal(showToast: Boolean) {
        val result = TextExtractHelper.extract(binding.receiptText.text.toString())
        // 只在字段为空时填入，避免覆盖用户已修改的内容
        if (binding.ocrStationInput.text.isNullOrBlank()) result.gasStation?.let { binding.ocrStationInput.setText(it) }
        if (binding.ocrLitersInput.text.isNullOrBlank()) result.fuelLiters?.let { binding.ocrLitersInput.setText(it.toString()) }
        if (binding.ocrCostInput.text.isNullOrBlank()) result.fuelCost?.let { binding.ocrCostInput.setText(it.toString()) }
        if (binding.ocrTypeInput.text.isNullOrBlank()) result.fuelType?.let { binding.ocrTypeInput.setText(it) }
        if (showToast) {
            val count = listOf(result.gasStation, result.fuelLiters, result.fuelCost, result.fuelType).count { it != null }
            toast(if (count > 0) "已提取 $count 项信息，请核对" else "未提取到信息，请检查文本或手动填写")
        }
    }
}
