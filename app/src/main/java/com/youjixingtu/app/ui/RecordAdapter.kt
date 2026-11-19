package com.youjixingtu.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.databinding.ItemRecordBinding
import com.youjixingtu.app.model.FuelRecord

class RecordAdapter(
    private val items: List<FuelRecord>,
    private val onEdit: (FuelRecord) -> Unit,
    private val onDelete: (FuelRecord) -> Unit
) : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    inner class RecordViewHolder(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val binding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RecordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = items[position]
        val b = holder.binding
        val date = Analytics.parseDate(record.date)
        b.itemDateText.text = "${Analytics.formatDateOnly(date)} ${Analytics.formatTimeOnly(date)}"
        b.itemMileageText.text = if (record.mileageForgotten) "[已忘记]" else "${record.mileage ?: "--"} km"
        b.itemBadgeText.visibility = if (record.isRefuel) View.VISIBLE else View.GONE
        b.itemBadgeText.text = if (record.tankStatus == "full") "加满" else "加油"
        b.itemDestinationText.text = "目的地: ${record.destination.ifBlank { "未填写" }}"

        val data = record.refuelData
        if (data != null) {
            val station = record.fuelValue("gasStation") ?: "--"
            val type = record.fuelValue("fuelType") ?: "--"
            val liters = record.fuelValue("fuelLiters") ?: "--"
            val cost = record.fuelValue("fuelCost") ?: "--"
            b.itemRefuelText.text = "⛽ $station ($type) - ${liters}升 / ¥$cost"
            b.itemRefuelText.visibility = View.VISIBLE
        } else {
            b.itemRefuelText.visibility = View.GONE
        }

        b.itemEditBtn.setOnClickListener { onEdit(record) }
        b.itemDeleteBtn.setOnClickListener { onDelete(record) }
    }

    override fun getItemCount(): Int = items.size
}
