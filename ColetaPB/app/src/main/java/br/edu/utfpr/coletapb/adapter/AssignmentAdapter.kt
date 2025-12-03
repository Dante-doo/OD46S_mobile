package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.Assignment

class AssignmentAdapter(
    private val context: Context,
    private val assignments: List<Assignment>
) : BaseAdapter() {

    override fun getCount(): Int = assignments.size

    override fun getItem(position: Int): Assignment = assignments[position]

    override fun getItemId(position: Int): Long = assignments[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_truck, parent, false)

        val assignment = assignments[position]
        val tvTruckName = view.findViewById<TextView>(R.id.tvTruckName)
        val tvTruckPlate = view.findViewById<TextView>(R.id.tvTruckPlate)

        // Mostra a rota e o veículo
        val routeName = assignment.routeName ?: "Rota #${assignment.routeId}"
        tvTruckName.text = if (assignment.isCurrent) {
            "▶ $routeName (Em execução)"
        } else {
            routeName
        }
        tvTruckPlate.text = "Veículo: ${assignment.vehiclePlate ?: "N/A"}"
        
        // Destaca visualmente a rota atual
        if (assignment.isCurrent) {
            view.setBackgroundColor(0xFF4CAF50.toInt()) // Verde claro
            tvTruckName.setTextColor(0xFFFFFFFF.toInt()) // Branco
            tvTruckPlate.setTextColor(0xFFFFFFFF.toInt()) // Branco
        } else {
            view.setBackgroundColor(0x00000000.toInt()) // Transparente
            tvTruckName.setTextColor(0xFF000000.toInt()) // Preto
            tvTruckPlate.setTextColor(0xFF666666.toInt()) // Cinza
        }

        return view
    }
}

