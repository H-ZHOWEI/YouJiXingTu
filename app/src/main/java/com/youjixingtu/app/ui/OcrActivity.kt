package com.youjixingtu.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
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
            // D2：大图先降采样，避免高分辨率照片导致 ML Kit OOM
            val image = buildInputImage(path)
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

    /** D2：超过 1600px 的图片降采样后再识别，保留 EXIF 旋转信息 */
    private fun buildInputImage(path: String): InputImage {
        val file = File(path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxSide <= 1600) return InputImage.fromFilePath(this, Uri.fromFile(file))

        val sample = maxOf(1, maxSide / 1600)
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return InputImage.fromFilePath(this, Uri.fromFile(file))
        val rotation = try {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        return InputImage.fromBitmap(bitmap, rotation)
    }

    private fun extract() = extractInternal(showToast = true)

    private fun extractInternal(showToast: Boolean) {
        val result = TextExtractHelper.extract(binding.receiptText.text.toString())
        // 只在字段为空时填入，避免覆盖用户已修改的内容
        if (binding.ocrStationInput.text.isNullOrBlank()) result.gasStation?.let { binding.ocrStationInput.setText(it) }
        if (binding.ocrLitersInput.text.isNullOrBlank()) result.fuelLiters?.let { binding.ocrLitersInput.setText(it.toString()) }
        if (binding.ocrCostInput.text.isNullOrBlank()) result.fuelCost?.let { binding.ocrCostInput.setText(it.toString()) }
        if (binding.ocrTypeInput.text.isNullOrBlank()) result.fuelType?.let { binding.ocrTypeInput.setText(it) }

        val hints = mutableListOf<String>()
        val station = binding.ocrStationInput.text?.toString()?.trim().orEmpty()
        val records = Repository.loadRecords()

        // I6：缺一项时用历史单价反推补全（标注“估算”）
        val histPrice = Analytics.historicalPricePerLiter(records, station.ifBlank { null })
            ?: Analytics.historicalPricePerLiter(records)
        if (histPrice != null && histPrice > 0) {
            val litersTxt = binding.ocrLitersInput.text?.toString()?.trim().orEmpty()
            val costTxt = binding.ocrCostInput.text?.toString()?.trim().orEmpty()
            if (litersTxt.isBlank() && costTxt.toDoubleOrNull() != null && result.fuelLiters == null) {
                val est = costTxt.toDouble() / histPrice
                binding.ocrLitersInput.setText(String.format(java.util.Locale.getDefault(), "%.2f", est))
                hints.add("加油量按历史单价 $histPrice 元/升估算，请核对")
            } else if (costTxt.isBlank() && litersTxt.toDoubleOrNull() != null && result.fuelCost == null) {
                val est = litersTxt.toDouble() * histPrice
                binding.ocrCostInput.setText(String.format(java.util.Locale.getDefault(), "%.2f", est))
                hints.add("金额按历史单价 $histPrice 元/升估算，请核对")
            }
        }

        // I5：单价交叉校验——识别出的金额/升数算出的单价与历史偏差过大时提醒
        val liters = binding.ocrLitersInput.text?.toString()?.toDoubleOrNull()
        val cost = binding.ocrCostInput.text?.toString()?.toDoubleOrNull()
        if (liters != null && liters > 0 && cost != null && cost > 0 && histPrice != null && histPrice > 0) {
            val price = cost / liters
            val deviation = kotlin.math.abs(price - histPrice) / histPrice
            val priceTxt = String.format(java.util.Locale.getDefault(), "%.2f", price)
            val histTxt = String.format(java.util.Locale.getDefault(), "%.2f", histPrice)
            if (deviation > 0.3) {
                hints.add("⚠ 本次单价约 $priceTxt 元/升，与历史 $histTxt 元/升差 ${(deviation * 100).toInt()}%，请仔细核对金额和升数")
            } else {
                hints.add("单价约 $priceTxt 元/升，与历史接近 ✓")
            }
        }

        if (hints.isNotEmpty()) {
            binding.ocrSmartHint.visibility = android.view.View.VISIBLE
            binding.ocrSmartHint.text = hints.joinToString("\n")
        } else {
            binding.ocrSmartHint.visibility = android.view.View.GONE
        }

        if (showToast) {
            val count = listOf(result.gasStation, result.fuelLiters, result.fuelCost, result.fuelType).count { it != null }
            toast(if (count > 0) "已提取 $count 项信息，请核对" else "未提取到信息，请检查文本或手动填写")
        }
    }
}
