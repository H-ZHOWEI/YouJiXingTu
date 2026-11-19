package com.youjixingtu.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.youjixingtu.app.core.Analytics.StationStat
import com.youjixingtu.app.databinding.ItemStationBinding

class StationAdapter(private val items: List<StationStat>) : RecyclerView.Adapter<StationAdapter.StationViewHolder>() {

    inner class StationViewHolder(val binding: ItemStationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val binding = ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        val item = items[position]
        holder.binding.stationNameText.text = item.name
        holder.binding.stationCountText.text = "${item.count} 次加油记录"
        holder.binding.stationConsumptionText.text = formatNumber(item.avgConsumption, 2)
        holder.binding.stationPriceText.text = formatNumber(item.avgPricePerLiter, 2)
        holder.binding.stationLitersText.text = formatNumber(item.avgFuelLiters, 1)
        holder.binding.stationRatingText.text = formatNumber(item.rating, 1)
        holder.binding.stationTypesText.text = "油品: ${item.fuelTypes.ifBlank { "--" }}"
    }

    override fun getItemCount(): Int = items.size
}
