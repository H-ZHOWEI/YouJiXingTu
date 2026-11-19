package com.youjixingtu.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun View.show(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun EditText.doubleOrNull(): Double? = text.toString().trim().toDoubleOrNull()

fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

fun fuelTypeValue(position: Int): String = when (position) {
    1 -> "92"
    2 -> "95"
    3 -> "98"
    4 -> "0"
    else -> ""
}

fun fuelTypePosition(value: String?): Int = when (value) {
    "92" -> 1
    "95" -> 2
    "98" -> 3
    "0" -> 4
    else -> 0
}

fun Spinner.selectedFuelType(): String = fuelTypeValue(selectedItemPosition)

fun formatNumber(value: Double, digits: Int = 2): String = String.format("%.${digits}f", value)

fun simpleTextWatcher(onChange: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s.toString())
    override fun afterTextChanged(s: Editable?) {}
}
