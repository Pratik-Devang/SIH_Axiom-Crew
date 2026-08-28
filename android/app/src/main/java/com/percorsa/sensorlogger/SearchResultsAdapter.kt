package com.percorsa.sensorlogger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SearchResultsAdapter(
    private val onSelect: (GeocodingResult) -> Unit
) : ListAdapter<GeocodingResult, SearchResultsAdapter.VH>(DIFF) {

    var userLocation: LatLon? = null

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView     = view.findViewById(R.id.tvResultName)
        val tvAddress: TextView  = view.findViewById(R.id.tvResultAddress)
        val tvIcon: TextView     = view.findViewById(R.id.tvResultIcon)
        val tvDistance: TextView = view.findViewById(R.id.tvResultDistance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvAddress.text = item.address.ifBlank { item.category }
        holder.tvIcon.text = categoryIcon(item.category, item.name)

        val userLoc = userLocation
        if (userLoc != null && userLoc.lat != 0.0) {
            val distM = distanceHaversine(userLoc.lat, userLoc.lon, item.location.lat, item.location.lon)
            holder.tvDistance.visibility = View.VISIBLE
            holder.tvDistance.text = if (distM < 1000) "%.0f m".format(Locale.US, distM)
            else "%.1f km".format(Locale.US, distM / 1000.0)
        } else {
            holder.tvDistance.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onSelect(item) }
    }

    private fun categoryIcon(category: String, name: String): String = when {
        name.equals("Home", ignoreCase = true) -> "🏠"
        name.equals("Work", ignoreCase = true) -> "💼"
        category.contains("restaurant") || category.contains("food") -> "🍽"
        category.contains("hospital")   || category.contains("health") -> "🏥"
        category.contains("school")     || category.contains("education") -> "🏫"
        category.contains("fuel")       || category.contains("petrol") -> "⛽"
        category.contains("parking")                                   -> "🅿"
        category.contains("tourism")                                   -> "🗺"
        category.contains("shop")                                      -> "🛍"
        category.contains("highway")                                   -> "🛣"
        category.contains("railway")                                   -> "🚉"
        category.contains("aeroway")                                   -> "✈"
        else                                                           -> "📍"
    }

    private fun distanceHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).let { it * it }
        return r * 2.0 * asin(sqrt(a))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GeocodingResult>() {
            override fun areItemsTheSame(a: GeocodingResult, b: GeocodingResult) = a.id == b.id
            override fun areContentsTheSame(a: GeocodingResult, b: GeocodingResult) = a == b
        }
    }
}
