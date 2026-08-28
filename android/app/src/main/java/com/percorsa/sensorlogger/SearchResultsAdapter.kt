package com.percorsa.sensorlogger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SearchResultsAdapter(
    private val onSelect: (GeocodingResult) -> Unit
) : ListAdapter<GeocodingResult, SearchResultsAdapter.VH>(DIFF) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView    = view.findViewById(R.id.tvResultName)
        val tvAddress: TextView = view.findViewById(R.id.tvResultAddress)
        val tvIcon: TextView    = view.findViewById(R.id.tvResultIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvAddress.text = item.address.ifBlank { item.category }
        holder.tvIcon.text = categoryIcon(item.category)
        holder.itemView.setOnClickListener { onSelect(item) }
    }

    private fun categoryIcon(category: String): String = when {
        category.contains("amenity/restaurant") || category.contains("food") -> "🍽"
        category.contains("amenity/hospital")  || category.contains("health") -> "🏥"
        category.contains("amenity/school")    || category.contains("education") -> "🏫"
        category.contains("amenity/fuel")                                        -> "⛽"
        category.contains("tourism")                                             -> "🗺"
        category.contains("shop")                                                -> "🛍"
        category.contains("highway")                                             -> "🛣"
        category.contains("railway")                                             -> "🚉"
        category.contains("aeroway")                                             -> "✈"
        else                                                                     -> "📍"
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GeocodingResult>() {
            override fun areItemsTheSame(a: GeocodingResult, b: GeocodingResult) = a.id == b.id
            override fun areContentsTheSame(a: GeocodingResult, b: GeocodingResult) = a == b
        }
    }
}
