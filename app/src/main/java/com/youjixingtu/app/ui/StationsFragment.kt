package com.youjixingtu.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.youjixingtu.app.core.Analytics
import com.youjixingtu.app.data.Repository
import com.youjixingtu.app.databinding.FragmentStationsBinding

class StationsFragment : Fragment() {

    private var _binding: FragmentStationsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.stationsRecycler.layoutManager = LinearLayoutManager(requireContext())
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
        val stations = Analytics.stationComparisons(Repository.loadRecords())
        binding.stationEmptyText.show(stations.isEmpty())
        binding.stationsRecycler.show(stations.isNotEmpty())
        binding.stationsRecycler.adapter = StationAdapter(stations)
    }
}
