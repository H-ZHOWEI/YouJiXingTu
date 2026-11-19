package com.youjixingtu.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.youjixingtu.app.R
import java.util.Locale

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun EditText.doubleOrNull(): Double? = text.toString().trim().toDoubleOrNull()

fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

/** E3：油品值与数组资源统一取源，避免硬编码索引与 arrays.xml 隐性耦合 */
fun fuelTypeValue(context: Context, position: Int): String =
    context.resources.getStringArray(R.array.fuel_type_values).getOrNull(position).orEmpty()

fun fuelTypePosition(context: Context, value: String?): Int {
    val values = context.resources.getStringArray(R.array.fuel_type_values)
    return values.indexOf(value ?: "").let { if (it >= 0) it else 0 }
}

fun formatNumber(value: Double, digits: Int = 2): String =
    String.format(Locale.getDefault(), "%.${digits}f", value)

fun simpleTextWatcher(onChange: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s.toString())
    override fun afterTextChanged(s: Editable?) {}
}
