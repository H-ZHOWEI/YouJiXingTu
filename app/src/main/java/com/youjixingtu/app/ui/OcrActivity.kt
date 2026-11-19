package com.youjixingtu.app.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.youjixingtu.app.databinding.ActivityOcrBinding
import com.youjixingtu.app.util.TextExtractHelper
import java.io.File

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra("photo_path")
        if (!path.isNullOrBlank() && File(path).exists()) {
            binding.ocrPreview.setImageURI(Uri.fromFile(File(path)))
        }

        binding.receiptText.setText(TextExtractHelper.MOCK_RECEIPT)
        extract()

        binding.extractBtn.setOnClickListener { extract() }
        binding.cancelOcrBtn.setOnClickListener { finish() }
        binding.applyOcrBtn.setOnClickListener {
            val intent = intent.apply {
                putExtra("station", binding.ocrStationInput.text.toString().trim().ifBlank { null })
                putExtra("liters", binding.ocrLitersInput.text.toString().toDoubleOrNull() ?: -1.0)
                putExtra("cost", binding.ocrCostInput.text.toString().toDoubleOrNull() ?: -1.0)
                putExtra("type", binding.ocrTypeInput.text.toString().trim().ifBlank { null })
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun extract() {
        val result = TextExtractHelper.extract(binding.receiptText.text.toString())
        result.gasStation?.let { binding.ocrStationInput.setText(it) }
        result.fuelLiters?.let { binding.ocrLitersInput.setText(it.toString()) }
        result.fuelCost?.let { binding.ocrCostInput.setText(it.toString()) }
        result.fuelType?.let { binding.ocrTypeInput.setText(it) }
        toast("提取完成，请核对并修改")
    }
}
