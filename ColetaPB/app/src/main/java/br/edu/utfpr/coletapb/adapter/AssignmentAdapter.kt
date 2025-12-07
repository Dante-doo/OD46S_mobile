package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.Assignment

class AssignmentAdapter(
    private val context: Context,
    private val assignments: MutableList<Assignment>,
    private val onItemClick: (Assignment) -> Unit
) : BaseAdapter() {
    
    override fun getCount(): Int = assignments.size
    
    override fun getItem(position: Int): Assignment = assignments[position]
    
    override fun getItemId(position: Int): Long = assignments[position].id
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_assignment, parent, false)
        
        val assignment = getItem(position)
        
        val tvRouteName = view.findViewById<TextView>(R.id.tvRouteName)
        val tvDriver = view.findViewById<TextView>(R.id.tvDriver)
        val tvVehicle = view.findViewById<TextView>(R.id.tvVehicle)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvBadge = view.findViewById<TextView>(R.id.tvBadge)
        
        tvRouteName.text = assignment.routeName ?: "Rota sem nome"
        tvDriver.text = "Motorista: ${assignment.driverName ?: "N/A"}"
        tvVehicle.text = "Caminhão: ${assignment.vehiclePlate ?: "N/A"}"
        
        // Status da assignment
        val assignmentStatusText = when (assignment.status) {
            "ACTIVE" -> "Disponível"
            "COMPLETED" -> "Concluída"
            "CANCELLED" -> "Cancelada"
            else -> assignment.status
        }
        
        // Status da execução (se houver)
        val executionStatusText = if (assignment.isCurrent) {
            " · Em execução"
        } else {
            ""
        }
        
        tvStatus.text = "Status: $assignmentStatusText$executionStatusText"
        
        // Badge para rota atual (em execução)
        if (assignment.isCurrent) {
            tvBadge.visibility = View.VISIBLE
            tvBadge.text = "EM ANDAMENTO"
            tvBadge.setBackgroundColor(Color.parseColor("#4CAF50")) // Verde
        } else {
            tvBadge.visibility = View.GONE
        }
        
        // Clique
        view.setOnClickListener {
            onItemClick(assignment)
        }
        
        return view
    }
}
