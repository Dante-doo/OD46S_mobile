package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.VehicleDto

class ItemTruck(private val context: Context, private var trucks: List<VehicleDto>) : BaseAdapter() {

    override fun getCount(): Int = trucks.size

    override fun getItem(position: Int): VehicleDto = trucks[position]

    override fun getItemId(position: Int): Long = trucks[position].id

    fun updateData(newTrucks: List<VehicleDto>) {
        this.trucks = newTrucks
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_truck, parent, false)

        val truck = getItem(position)

        val tvName = view.findViewById<TextView>(R.id.tvTruckName)
        val tvPlate = view.findViewById<TextView>(R.id.tvTruckPlate)

        tvName.text = "${truck.brand} ${truck.model}"
        tvPlate.text = truck.license_plate

        return view
    }
}